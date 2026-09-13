package org.tornotron.echno_backend.billing.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.Subscription;
import org.tornotron.echno_backend.billing.components.SubscriptionCache;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.billing.dto.BillingEventSummaryDto;
import org.tornotron.echno_backend.billing.dto.BillingMapper;
import org.tornotron.echno_backend.billing.dto.BillingProviderInfoDto;
import org.tornotron.echno_backend.billing.dto.CheckoutMandateTermsDto;
import org.tornotron.echno_backend.billing.dto.CheckoutSessionCreateDto;
import org.tornotron.echno_backend.billing.dto.CheckoutSessionDto;
import org.tornotron.echno_backend.billing.dto.MandateAcknowledgeDto;
import org.tornotron.echno_backend.billing.dto.MandateDto;
import org.tornotron.echno_backend.billing.dto.SubscriptionDto;
import org.tornotron.echno_backend.billing.dto.VerifyCheckoutDto;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.BillingEvent;
import org.tornotron.echno_backend.billing.gateway.BillingGateway;
import org.tornotron.echno_backend.billing.gateway.BillingGatewayProperties;
import org.tornotron.echno_backend.billing.gateway.BillingNotConfiguredException;
import org.tornotron.echno_backend.billing.gateway.MandateMethod;
import org.tornotron.echno_backend.billing.gateway.MandatePolicy;
import org.tornotron.echno_backend.billing.gateway.MandatePolicyViolationException;
import org.tornotron.echno_backend.billing.gateway.NormalizedEventType;
import org.tornotron.echno_backend.billing.gateway.NormalizedSubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.gateway.dto.CheckoutSignature;
import org.tornotron.echno_backend.billing.gateway.dto.CreateSubscriptionCommand;
import org.tornotron.echno_backend.billing.gateway.dto.GatewaySubscription;
import org.tornotron.echno_backend.billing.gateway.dto.NormalizedBillingEvent;
import org.tornotron.echno_backend.billing.gateway.dto.NotifyInfo;
import org.tornotron.echno_backend.billing.gateway.dto.OrgBillingProfile;
import org.tornotron.echno_backend.billing.repositories.BillingEventRepository;
import org.tornotron.echno_backend.billing.repositories.CheckoutSessionRepository;
import org.tornotron.echno_backend.billing.repositories.PaymentMandateRepository;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.billing.webhook.EntitlementProjection;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.PlanNotFoundException;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * The hosted checkout the web drives: which provider is wired, opening a provider
 * subscription for a plan, verifying the buyer's result, the mandate terms, and the
 * organization's billing history.
 *
 * <p>Entitlement is written by exactly one class, {@link EntitlementProjection}. The verify
 * step does not activate a row on its own; it hands the projection the same activation the
 * webhook would carry, keyed on the provider subscription id, so whichever of the two arrives
 * first activates and the other finds the row already live. That is what makes verify
 * idempotent and the pair safe to race.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutService {

    /** Hours of notice the buyer gets before each recurring debit (RBI e-mandate framework). */
    public static final int PRE_DEBIT_NOTICE_HOURS = 24;
    /** Margin past the session's expiry before the provider expires the subscription too. */
    static final long PROVIDER_EXPIRY_MARGIN_MINUTES = 5;
    static final int HISTORY_PAGE_SIZE = 200;

    private final BillingGateway gateway;
    private final BillingGatewayProperties properties;
    private final MandatePolicy mandatePolicy;
    private final PlanRepository plans;
    private final SubscriptionRepository subscriptions;
    private final CheckoutSessionRepository sessions;
    private final PaymentMandateRepository mandates;
    private final BillingEventRepository events;
    private final OrganizationRepository organizations;
    private final SubscriptionService subscriptionService;
    private final EntitlementProjection projection;
    private final SubscriptionCache cache;
    private final EntityManager entityManager;
    private final TransactionalWorkRunner transactions;

    private final ObjectMapper json = new ObjectMapper();

    /** What the browser checkout needs to know before it starts; well formed under NONE too. */
    public BillingProviderInfoDto providerInfo() {
        boolean live = gateway.isEnabled();
        boolean ready = checkoutReady();
        return BillingProviderInfoDto.builder()
                .provider(BillingMapper.providerName(live ? gateway.providerId() : ProviderId.MANUAL))
                .enabled(ready)
                .keyId(live ? gateway.publicKeyId() : null)
                .currency(properties.getCurrency())
                .afaCapPaise(mandatePolicy.afaCapPaise())
                .preDebitNoticeHours(PRE_DEBIT_NOTICE_HOURS)
                .supportedFlows(ready ? List.of("SUBSCRIPTION") : List.of())
                .build();
    }

    /** One answer for "can a browser checkout start": a live gateway that also has a public key to hand out. */
    private boolean checkoutReady() {
        if (!gateway.isEnabled()) {
            return false;
        }
        String keyId = gateway.publicKeyId();
        return keyId != null && !keyId.isBlank();
    }

    /**
     * Opens a checkout for the organization on a plan. A free plan is activated directly
     * through the subscription service and never reaches the provider. A paid plan needs a
     * configured provider, no live subscription on the same plan, and the buyer's acceptance
     * when the cycle is above the RBI cap; the provider subscription is then created through
     * the port, a projection row is written INCOMPLETE so the webhook and the verify step
     * converge on it, and the session row records what the browser widget needs.
     *
     * <p>Deliberately not one transaction. The provider round trips (customer, plan, subscription)
     * run outside any database transaction: a rollback after them would leave provider objects
     * with no local twin, and three HTTP calls inside a serializable transaction held the row
     * locks against the webhook projector and the sweep for as long as the provider took. The
     * pre-check reads in one short transaction, the provider is called, and the rows are
     * written in another (both through {@link TransactionalWorkRunner}, so the tenant filter is
     * on for them); if that write fails the provider subscription just created is cancelled
     * again so nothing is orphaned.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CheckoutSessionDto createSession(Long organizationId, Long userId, CheckoutSessionCreateDto request) {
        Prepared prepared = transactions.runInTransaction(() -> prepare(organizationId, request));
        if (prepared.free()) {
            SubscriptionDto activated = subscriptionService.createSubscription(organizationId, userId, prepared.plan().getCode(), prepared.period());
            log.info("Free plan {} activated directly for organization {} (no gateway)", prepared.plan().getCode(), organizationId);
            return CheckoutSessionDto.builder()
                    .provider(BillingMapper.providerName(ProviderId.MANUAL))
                    .planCode(prepared.plan().getCode())
                    .billingPeriod(prepared.period())
                    .amountPaise(0)
                    .currency(prepared.currency())
                    .recurring(false)
                    .subscription(activated)
                    .build();
        }
        if (prepared.open() != null) {
            log.info("Reusing open checkout session {} for organization {} on plan {}",
                    prepared.open().getId(), organizationId, prepared.plan().getCode());
            return toDto(prepared.open(), prepared.organization());
        }
        Plan plan = prepared.plan();
        BillingPeriod period = prepared.period();
        Organization organization = prepared.organization();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(checkoutWindowMinutes(), ChronoUnit.MINUTES);
        NotifyInfo notify = new NotifyInfo(organization.getOrganizationEmail(), organization.getOrganizationPhone());
        int trialDays = Optional.ofNullable(plan.getTrialDays()).orElse(0);
        CreateSubscriptionCommand command = new CreateSubscriptionCommand(
                organizationId, plan.getCode(), period, 1, trialDays, null, notify, request.isAcceptPerChargeAfa(),
                expiresAt.plus(PROVIDER_EXPIRY_MARGIN_MINUTES, ChronoUnit.MINUTES));
        // The rules hold here regardless of what the adapter checks, so a violation never reaches the provider.
        mandatePolicy.validateCreate(command, prepared.cycleAmount());

        // Provider calls, outside any transaction. Each adapter call records its own mapping
        // row as soon as the provider hands back an id.
        gateway.ensureCustomer(new OrgBillingProfile(organizationId, organization.getOrganizationName(), null,
                organization.getOrganizationEmail(), organization.getOrganizationPhone(), prepared.currency()));
        gateway.ensurePlan(plan, period);
        GatewaySubscription created = gateway.createSubscription(command);
        ProviderId provider = gateway.providerId();

        CheckoutSession session;
        try {
            session = transactions.runInTransaction(() -> persistSession(organizationId, userId, request, prepared, created, provider, now, expiresAt));
        } catch (RuntimeException e) {
            compensate(created, organizationId, e);
            throw e;
        }
        log.info("Checkout session {} opened: organization {} plan {} ({}) provider subscription {}, expires {}",
                session.getId(), organizationId, plan.getCode(), period, created.providerSubscriptionId(), expiresAt);
        return toDto(session, organization);
    }

    /** What the read pre-check found, for the steps after it. */
    private record Prepared(Plan plan, BillingPeriod period, long cycleAmount, String currency,
                            Organization organization, CheckoutSession open) {
        boolean free() {
            return cycleAmount <= 0;
        }
    }

    private Prepared prepare(Long organizationId, CheckoutSessionCreateDto request) {
        Plan plan = plans.findByCodeWithFeatures(request.getPlanCode())
                .orElseThrow(() -> new PlanNotFoundException("Plan with code '" + request.getPlanCode() + "' was not found"));
        BillingPeriod period = Optional.ofNullable(request.getBillingPeriod()).orElse(BillingPeriod.MONTHLY);
        long cycleAmount = MandatePolicy.cycleAmountPaise(plan, period);
        String currency = Optional.ofNullable(plan.getCurrency()).orElse(properties.getCurrency());
        if (cycleAmount <= 0) {
            return new Prepared(plan, period, cycleAmount, currency, null, null);
        }
        if (!checkoutReady()) {
            throw new BillingNotConfiguredException();
        }
        // Already live on this plan: nothing to buy. Live on another plan through a provider:
        // a second provider subscription would bill twice, so that goes through change-plan.
        // A manual or trial row on another plan is what a paid checkout replaces; the
        // projection supersedes it when the new subscription activates.
        subscriptions.findActiveSubscription(organizationId).ifPresent(live -> {
            boolean samePlan = live.getPlan() != null && plan.getCode().equals(live.getPlan().getCode());
            if (samePlan) {
                throw new DuplicateResourceException("Organization " + organizationId
                        + " already has an active subscription on plan '" + plan.getCode() + "'");
            }
            if (live.getProvider() != null && live.getProvider() != ProviderId.MANUAL) {
                throw new DuplicateResourceException("Organization " + organizationId
                        + " already has an active " + live.getProvider() + " subscription on plan '"
                        + live.getPlan().getCode() + "'; use change-plan to switch plans instead");
            }
        });
        Organization organization = organizations.findById(organizationId)
                .orElseThrow(() -> new EntityNotFoundException("Organization " + organizationId + " was not found"));
        CheckoutSession open = sessions
                .findFirstByOrganization_IdAndPlanCodeAndBillingPeriodAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                        organizationId, plan.getCode(), period, CheckoutSessionStatus.OPEN, Instant.now())
                .orElse(null);
        return new Prepared(plan, period, cycleAmount, currency, organization, open);
    }

    private CheckoutSession persistSession(Long organizationId, Long userId, CheckoutSessionCreateDto request, Prepared prepared,
                                           GatewaySubscription created, ProviderId provider, Instant now, Instant expiresAt) {
        Plan plan = prepared.plan();
        BillingPeriod period = prepared.period();
        subscriptions.findByProviderAndExternalSubscriptionId(provider, created.providerSubscriptionId())
                .orElseGet(() -> subscriptions.save(Subscription.builder()
                        .organizationId(organizationId)
                        .userId(userId)
                        .plan(plan)
                        .provider(provider)
                        .externalSubscriptionId(created.providerSubscriptionId())
                        .status(SubscriptionStatus.INCOMPLETE)
                        .currentPeriodStart(now)
                        .currentPeriodEnd(now.plus(periodDays(period), ChronoUnit.DAYS))
                        .build()));
        return sessions.save(CheckoutSession.builder()
                .organization(prepared.organization())
                .provider(provider)
                .planCode(plan.getCode())
                .billingPeriod(period)
                .providerSubscriptionId(created.providerSubscriptionId())
                .amountPaise(prepared.cycleAmount())
                .currency(prepared.currency())
                .recurring(true)
                .perChargeApproval(mandatePolicy.requiresPerChargeAfa(prepared.cycleAmount()))
                .perChargeApprovalAccepted(request.isAcceptPerChargeAfa())
                .mandateAmountCapPaise(prepared.cycleAmount())
                .authUrl(created.authUrl())
                .status(CheckoutSessionStatus.OPEN)
                .createdByUserId(userId)
                .expiresAt(expiresAt)
                .build());
    }

    /** The local write failed after the provider subscription existed: cancel it so it is not orphaned. */
    private void compensate(GatewaySubscription created, Long organizationId, RuntimeException cause) {
        log.error("Checkout rows for organization {} could not be written after provider subscription {} was created: {}; cancelling it",
                organizationId, created.providerSubscriptionId(), cause.getMessage());
        try {
            gateway.cancelSubscription(created.providerSubscriptionId(), false);
        } catch (RuntimeException e) {
            log.error("Provider subscription {} could not be cancelled after the failed checkout write; it is orphaned at the provider: {}",
                    created.providerSubscriptionId(), e.getMessage(), e);
        }
    }

    private long checkoutWindowMinutes() {
        return Math.max(1, properties.getCheckoutWindowMinutes());
    }

    /**
     * Verifies the buyer's result against the session and activates the entitlement once.
     * Deliberately not one transaction: the projection runs in its own, exactly as it does for
     * a webhook, so the two paths take the same locks in the same order and the second to
     * arrive finds the row already live. A session already VERIFIED returns the row as is.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SubscriptionDto verify(Long organizationId, VerifyCheckoutDto request) {
        if (!gateway.isEnabled()) {
            throw new BillingNotConfiguredException();
        }
        ProviderId provider = gateway.providerId();
        CheckoutSession session = findSession(provider, request)
                .orElseThrow(() -> new EntityNotFoundException("No checkout session matches the payment result"));
        if (!organizationId.equals(session.getOrganization().getId())) {
            throw new EntityNotFoundException("No checkout session matches the payment result");
        }
        if (session.getStatus() == CheckoutSessionStatus.VERIFIED) {
            log.info("Checkout session {} already verified; returning the projected row", session.getId());
            return projectedRow(provider, session);
        }
        CheckoutSignature result = new CheckoutSignature(request.getProviderPaymentId(),
                request.getProviderSubscriptionId(), request.getProviderOrderId(), request.getProviderSignature());
        if (!gateway.verifyCheckoutSignature(result)) {
            log.warn("Checkout session {} presented a signature that does not verify", session.getId());
            throw new InvalidRequestException("The payment signature does not verify against the checkout session");
        }

        Optional<Subscription> existing = subscriptions.findByProviderAndExternalSubscriptionId(provider, session.getProviderSubscriptionId());
        if (existing.map(Subscription::isActive).orElse(false)) {
            log.info("Checkout session {}: subscription {} already live (webhook arrived first); nothing to project",
                    session.getId(), existing.get().getId());
        } else {
            Plan plan = plans.findByCodeWithFeatures(session.getPlanCode())
                    .orElseThrow(() -> new PlanNotFoundException("Plan with code '" + session.getPlanCode() + "' was not found"));
            // The projection commits in its own transaction. A persistence context bound to this
            // thread (open-in-view on a request) would otherwise hand back the instance it read
            // above, still INCOMPLETE, so that instance is dropped before the row is read again.
            existing.filter(entityManager::contains).ifPresent(entityManager::detach);
            try {
                String outcome = projection.apply(organizationId, activation(provider, session, plan));
                log.info("Checkout session {} verified: {}", session.getId(), outcome);
            } catch (DataIntegrityViolationException e) {
                // The webhook inserted the same provider subscription between the read above and
                // the projection's insert; the unique key refused a second row. The winner's
                // row is what is read back below.
                log.info("Checkout session {}: the webhook projected {} first; reading its row", session.getId(), session.getProviderSubscriptionId());
            }
        }

        session.setStatus(CheckoutSessionStatus.VERIFIED);
        session.setProviderPaymentId(request.getProviderPaymentId());
        session.setVerifiedAt(Instant.now());
        Subscription projected = subscriptions.findByProviderAndExternalSubscriptionId(provider, session.getProviderSubscriptionId())
                .orElseThrow(() -> new IllegalStateException("Projection row missing after activation of session " + session.getId()));
        session.setSubscriptionId(projected.getId());
        sessions.save(session);
        cache.evictOnWrite(organizationId);
        return BillingMapper.toSubscriptionDto(projected);
    }

    /** The organization's most recent registered mandate, if any. */
    @Transactional(readOnly = true)
    public Optional<MandateDto> currentMandate(Long organizationId) {
        return mandates.findFirstByOrganization_IdOrderByCreatedAtDesc(organizationId).map(BillingMapper::toMandateDto);
    }

    /**
     * Records the buyer's acknowledgement of the mandate terms for a plan and cycle, and returns
     * the constraints. The mandate itself is registered by the provider inside the subscription
     * authorization; this step is the buyer's explicit acceptance the RBI rules ask for.
     */
    @Transactional
    public CheckoutMandateTermsDto acknowledgeMandate(Long organizationId, MandateAcknowledgeDto request) {
        Plan plan = plans.findByCodeWithFeatures(request.getPlanCode())
                .orElseThrow(() -> new PlanNotFoundException("Plan with code '" + request.getPlanCode() + "' was not found"));
        BillingPeriod period = Optional.ofNullable(request.getBillingPeriod()).orElse(BillingPeriod.MONTHLY);
        long cycleAmount = MandatePolicy.cycleAmountPaise(plan, period);
        if (cycleAmount <= 0) {
            throw new MandatePolicyViolationException("Plan '" + plan.getCode() + "' is free on the " + period + " cycle; no mandate is needed");
        }
        boolean perCharge = mandatePolicy.requiresPerChargeAfa(cycleAmount);
        if (perCharge && !request.isAcceptPerChargeAfa()) {
            throw new MandatePolicyViolationException("Plan '" + plan.getCode() + "' debits " + cycleAmount
                    + " paise per cycle, above the RBI cap of " + mandatePolicy.afaCapPaise()
                    + " paise; each payment will need the payer to authenticate, which the buyer must accept explicitly");
        }
        Organization organization = organizations.findById(organizationId)
                .orElseThrow(() -> new EntityNotFoundException("Organization " + organizationId + " was not found"));
        if (!new NotifyInfo(organization.getOrganizationEmail(), organization.getOrganizationPhone()).hasChannel()) {
            throw new MandatePolicyViolationException(
                    "The payer must be reachable for the 24 hour pre-debit notification; add an email or a phone number to the organization");
        }
        MandateMethod method = Optional.ofNullable(request.getMethod()).orElse(MandateMethod.UNKNOWN);
        sessions.findFirstByOrganization_IdAndPlanCodeAndBillingPeriodAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                        organizationId, plan.getCode(), period, CheckoutSessionStatus.OPEN, Instant.now())
                .ifPresent(session -> {
                    session.setPerChargeApprovalAccepted(request.isAcceptPerChargeAfa());
                    if (method != MandateMethod.UNKNOWN) {
                        session.setMandateMethod(method);
                    }
                    sessions.save(session);
                });
        return CheckoutMandateTermsDto.builder()
                .amountCapPaise(cycleAmount)
                .preDebitNoticeHours(PRE_DEBIT_NOTICE_HOURS)
                .method(method)
                .perChargeApproval(perCharge)
                .build();
    }

    /** The organization's billing events, newest first. Scoped by the organization id on the row. */
    @Transactional(readOnly = true)
    public List<BillingEventSummaryDto> listEvents(Long organizationId) {
        return events.findByOrganizationIdOrderByReceivedAtDesc(organizationId, PageRequest.of(0, HISTORY_PAGE_SIZE)).stream()
                .map(this::summarize)
                .toList();
    }

    // -- helpers --------------------------------------------------------------------------

    private Optional<CheckoutSession> findSession(ProviderId provider, VerifyCheckoutDto request) {
        if (request.getProviderSubscriptionId() != null && !request.getProviderSubscriptionId().isBlank()) {
            return sessions.findFirstByProviderAndProviderSubscriptionId(provider, request.getProviderSubscriptionId());
        }
        if (request.getProviderOrderId() != null && !request.getProviderOrderId().isBlank()) {
            return sessions.findFirstByProviderAndProviderOrderId(provider, request.getProviderOrderId());
        }
        return Optional.empty();
    }

    private SubscriptionDto projectedRow(ProviderId provider, CheckoutSession session) {
        return subscriptions.findByProviderAndExternalSubscriptionId(provider, session.getProviderSubscriptionId())
                .map(BillingMapper::toSubscriptionDto)
                .orElseThrow(() -> new IllegalStateException("Verified session " + session.getId() + " has no projection row"));
    }

    /** The activation the webhook would carry, so the projection treats verify exactly like it. */
    static NormalizedBillingEvent activation(ProviderId provider, CheckoutSession session, Plan plan) {
        Instant now = Instant.now();
        int trialDays = Optional.ofNullable(plan.getTrialDays()).orElse(0);
        boolean trial = trialDays > 0;
        Instant periodEnd = now.plus(trial ? trialDays : periodDays(session.getBillingPeriod()), ChronoUnit.DAYS);
        GatewaySubscription snapshot = new GatewaySubscription(session.getProviderSubscriptionId(), null, null,
                trial ? NormalizedSubscriptionStatus.TRIAL : NormalizedSubscriptionStatus.AUTHENTICATED,
                now, periodEnd, null, null, null);
        return new NormalizedBillingEvent(provider, "checkout-verify-" + session.getId(),
                NormalizedEventType.SUBSCRIPTION_AUTHENTICATED, now, session.getOrganization().getId(),
                session.getProviderSubscriptionId(), null, null, session.getPlanCode(), snapshot,
                null, null, null, null);
    }

    static long periodDays(BillingPeriod period) {
        return period == BillingPeriod.ANNUAL ? 365 : 30;
    }

    private CheckoutSessionDto toDto(CheckoutSession session, Organization organization) {
        return CheckoutSessionDto.builder()
                .id(session.getId())
                .provider(BillingMapper.providerName(session.getProvider()))
                .keyId(gateway.publicKeyId())
                .providerSubscriptionId(session.getProviderSubscriptionId())
                .providerOrderId(session.getProviderOrderId())
                .planCode(session.getPlanCode())
                .billingPeriod(session.getBillingPeriod())
                .amountPaise(session.getAmountPaise())
                .currency(session.getCurrency())
                .recurring(Boolean.TRUE.equals(session.getRecurring()))
                .authUrl(session.getAuthUrl())
                .customerEmail(organization.getOrganizationEmail())
                .customerContact(organization.getOrganizationPhone())
                .mandate(Boolean.TRUE.equals(session.getRecurring()) ? CheckoutMandateTermsDto.builder()
                        .amountCapPaise(Optional.ofNullable(session.getMandateAmountCapPaise()).orElse(session.getAmountPaise()))
                        .preDebitNoticeHours(PRE_DEBIT_NOTICE_HOURS)
                        .method(session.getMandateMethod())
                        .perChargeApproval(Boolean.TRUE.equals(session.getPerChargeApproval()))
                        .build() : null)
                .expiresAt(session.getExpiresAt())
                .build();
    }

    /** A fixed word per inbox state; the raw error text stays on the admin inbox view. */
    private static String describe(BillingEvent row) {
        if (row.getStatus() == null) {
            return null;
        }
        return switch (row.getStatus()) {
            case PROCESSED -> "applied";
            case SKIPPED -> "not applicable";
            case FAILED -> "not applied; under review";
            default -> "pending";
        };
    }

    private BillingEventSummaryDto summarize(BillingEvent row) {
        Long amount = null;
        String currency = null;
        String reference = null;
        try {
            JsonNode payload = json.readTree(row.getPayload() == null ? "{}" : row.getPayload()).path("payload");
            for (String kind : List.of("payment", "invoice")) {
                JsonNode entity = payload.path(kind).path("entity");
                if (entity.isObject()) {
                    if (amount == null && entity.hasNonNull("amount")) {
                        amount = entity.get("amount").asLong();
                        currency = entity.path("currency").asText(null);
                    }
                    if (reference == null && entity.hasNonNull("id")) {
                        reference = entity.get("id").asText();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Billing event {} payload is not parseable for the history page: {}", row.getId(), e.getMessage());
        }
        return BillingEventSummaryDto.builder()
                .id(row.getId())
                .eventType(row.getEventType())
                .providerEventId(row.getProviderEventId())
                .occurredAt(Optional.ofNullable(row.getOccurredAt()).orElse(row.getReceivedAt()))
                .amountPaise(amount)
                .currency(currency)
                .reference(reference)
                .status(row.getStatus() == null ? null : row.getStatus().name())
                .description(describe(row))
                .build();
    }
}
