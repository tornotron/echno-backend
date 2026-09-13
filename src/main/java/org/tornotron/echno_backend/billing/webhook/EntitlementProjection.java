package org.tornotron.echno_backend.billing.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.Subscription;
import org.tornotron.echno_backend.billing.components.SubscriptionCache;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.BillingGatewayException;
import org.tornotron.echno_backend.billing.gateway.MandateMethod;
import org.tornotron.echno_backend.billing.gateway.NormalizedEventType;
import org.tornotron.echno_backend.billing.gateway.NormalizedMandateStatus;
import org.tornotron.echno_backend.billing.gateway.PaymentMandate;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.gateway.dto.GatewaySubscription;
import org.tornotron.echno_backend.billing.gateway.dto.NormalizedBillingEvent;
import org.tornotron.echno_backend.billing.repositories.GatewayPlanMappingRepository;
import org.tornotron.echno_backend.billing.repositories.PaymentMandateRepository;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.organization.Organization;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Writes the entitlement projection, the {@link Subscription} row, from normalized gateway
 * events. The only writer of a provider-backed row's lifecycle fields. The gate keeps reading
 * {@code Subscription} and {@code PlanFeature} exactly as before; this is where the row comes
 * from once a provider is behind it.
 *
 * <p>A state function, not a step sequence: the target status is the provider's own state
 * snapshot mapped through {@code NormalizedSubscriptionStatus}, the period bounds are the
 * provider's, and an event type only decides what to do when the snapshot is absent
 * (a failed payment, a revoked mandate). Ordering is the projector's problem; by the time an
 * event reaches this class it has passed the watermark.
 *
 * <p>Runs pinned to the organization the event was resolved to, so the tenant-scoped rows it
 * touches (the mandate) are checked against that organization like any other write.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntitlementProjection {

    private static final List<SubscriptionStatus> LIVE_STATUSES =
            List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING, SubscriptionStatus.PAST_DUE);
    private static final long DEFAULT_PERIOD_DAYS = 30;

    /**
     * Prefix of an outcome that applied nothing and says why. The projector records such an
     * event as SKIPPED with the outcome as its reason, instead of PROCESSED.
     */
    public static final String SKIPPED = "skipped: ";

    private final SubscriptionRepository subscriptions;
    private final PlanRepository plans;
    private final GatewayPlanMappingRepository planMappings;
    private final PaymentMandateRepository mandates;
    private final SubscriptionCache cache;
    private final TenantScopedJobRunner tenantRunner;

    /**
     * Applies one event to the organization's entitlement.
     *
     * @param organizationId The organization the event was resolved to.
     * @param event The event.
     * @return A one-line description of what changed, for the inbox row.
     */
    // Its own transaction, so a failed projection rolls back alone and the projector can still
    // record the failure on the inbox row in its transaction.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String apply(Long organizationId, NormalizedBillingEvent event) {
        return tenantRunner.callForTenant(organizationId, () -> applyPinned(organizationId, event));
    }

    private String applyPinned(Long organizationId, NormalizedBillingEvent event) {
        String outcome = switch (event.type()) {
            case MANDATE_AUTHORIZED, MANDATE_REVOKED -> projectMandate(organizationId, event);
            case PAYMENT_FAILED -> projectPaymentFailed(organizationId, event);
            case INVOICE_PAID -> "invoice acknowledged; the charged event carries the period";
            case IGNORED -> event.mandateStatus() != null ? projectMandate(organizationId, event) : "no projection for this event";
            default -> projectSubscription(organizationId, event);
        };
        cache.evictOnWrite(organizationId);
        return outcome;
    }

    private String projectSubscription(Long organizationId, NormalizedBillingEvent event) {
        GatewaySubscription snapshot = event.subscription();
        if (snapshot == null) {
            throw new BillingGatewayException(event.type() + " carried no subscription snapshot to project");
        }
        Subscription row = subscriptions.findByProviderAndExternalSubscriptionId(event.provider(), snapshot.providerSubscriptionId())
                .orElseGet(() -> newRow(organizationId, event));
        requireSameOrganization(organizationId, row);
        SubscriptionStatus before = row.getStatus();
        SubscriptionStatus target = snapshot.status().toSubscriptionStatus();
        if (before == SubscriptionStatus.CANCELED && row.getCancelRequestedAt() != null && LIVE_STATUSES.contains(target)) {
            // The organization cancelled through the gateway. A charge the provider reports
            // after that is recorded (this event stays in the inbox) but never re-activates
            // the row; reconciliation re-sends the cancel until the provider agrees.
            log.warn("Provider reports {} for subscription {} ({}) after the organization cancelled it on {}; left CANCELED",
                    snapshot.status(), row.getId(), snapshot.providerSubscriptionId(), row.getCancelRequestedAt());
            return "CANCELED kept: provider reports " + target + " after the cancel of "
                    + row.getCancelRequestedAt() + "; reconciliation re-sends the cancel";
        }
        target = keepTrialing(row, before, target, snapshot);
        row.setStatus(target);
        trackPastDue(row, before, target, event.occurredAt());
        applyPeriod(row, snapshot, event);
        switchPlanIfChanged(row, event);
        if (target == SubscriptionStatus.CANCELED && before != SubscriptionStatus.CANCELED) {
            row.setCanceledAt(Optional.ofNullable(event.occurredAt()).orElse(Instant.now()));
            if (row.getCancellationReason() == null) {
                row.setCancellationReason(event.type() == NormalizedEventType.SUBSCRIPTION_COMPLETED
                        ? "Completed at the provider" : "Cancelled at the provider");
            }
        }
        if (snapshot.mandateReference() != null) {
            touchMandate(organizationId, event.provider(), snapshot.mandateReference(), snapshot.providerSubscriptionId(),
                    null, null, null, event.occurredAt());
        }
        row = subscriptions.save(row);
        if (row.isActive()) {
            supersede(row);
        }
        log.info("Entitlement projected: organization {} subscription {} ({}) {} -> {} via {}",
                organizationId, row.getId(), snapshot.providerSubscriptionId(), before, target, event.type());
        return before + " -> " + target;
    }

    private Subscription newRow(Long organizationId, NormalizedBillingEvent event) {
        Plan plan = resolvePlan(event);
        Instant now = Instant.now();
        return Subscription.builder()
                .organizationId(organizationId)
                .plan(plan)
                .provider(event.provider())
                .externalSubscriptionId(event.subscription().providerSubscriptionId())
                .status(SubscriptionStatus.INCOMPLETE)
                .currentPeriodStart(now)
                .currentPeriodEnd(now.plus(DEFAULT_PERIOD_DAYS, ChronoUnit.DAYS))
                .build();
    }

    private Plan resolvePlan(NormalizedBillingEvent event) {
        if (event.planCode() != null) {
            Optional<Plan> byCode = plans.findByCodeWithFeatures(event.planCode());
            if (byCode.isPresent()) {
                return byCode.get();
            }
        }
        if (event.providerPlanId() != null) {
            Optional<Plan> byMapping = planMappings.findFirstByProviderAndProviderPlanId(event.provider(), event.providerPlanId())
                    .flatMap(mapping -> plans.findByCodeWithFeatures(mapping.getPlanCode()));
            if (byMapping.isPresent()) {
                return byMapping.get();
            }
        }
        throw new BillingGatewayException("Cannot resolve the internal plan for provider subscription "
                + event.providerSubscriptionId() + " (plan code " + event.planCode() + ", provider plan " + event.providerPlanId() + ")");
    }

    /**
     * A row the verify step projected TRIALING stays TRIALING until its trial ends. The
     * provider's own snapshot for the same subscription says {@code authenticated} (ACTIVE)
     * with the first charge still ahead, which is the same fact in the provider's vocabulary;
     * flipping the status on it would show the organization ACTIVE with a trial still running.
     */
    private static SubscriptionStatus keepTrialing(Subscription row, SubscriptionStatus before, SubscriptionStatus target,
                                                   GatewaySubscription snapshot) {
        if (target != SubscriptionStatus.ACTIVE || before != SubscriptionStatus.TRIALING || row.getTrialEnd() == null) {
            return target;
        }
        Instant now = Instant.now();
        boolean chargeAhead = snapshot.nextChargeAt() == null || snapshot.nextChargeAt().isAfter(now);
        return row.getTrialEnd().isAfter(now) && chargeAhead ? SubscriptionStatus.TRIALING : target;
    }

    /**
     * Stamps when the row went past due, so the grace window counts from the failed renewal
     * and not from whenever the gate happens to look. Kept across repeated PAST_DUE events,
     * cleared by any other status.
     */
    private static void trackPastDue(Subscription row, SubscriptionStatus before, SubscriptionStatus target, Instant at) {
        if (target != SubscriptionStatus.PAST_DUE) {
            row.setPastDueSince(null);
        } else if (before != SubscriptionStatus.PAST_DUE || row.getPastDueSince() == null) {
            row.setPastDueSince(Optional.ofNullable(at).orElse(Instant.now()));
        }
    }

    private void applyPeriod(Subscription row, GatewaySubscription snapshot, NormalizedBillingEvent event) {
        if (snapshot.currentPeriodStart() != null) {
            row.setCurrentPeriodStart(snapshot.currentPeriodStart());
        }
        if (snapshot.currentPeriodEnd() != null) {
            row.setCurrentPeriodEnd(snapshot.currentPeriodEnd());
        } else if (row.isActive() && snapshot.nextChargeAt() != null && snapshot.nextChargeAt().isAfter(row.getCurrentPeriodEnd())) {
            // Authenticated with the first charge still ahead: entitled until that charge.
            row.setCurrentPeriodEnd(snapshot.nextChargeAt());
        }
        if (row.getStatus() == SubscriptionStatus.TRIALING && row.getTrialStart() == null) {
            row.setTrialStart(Optional.ofNullable(event.occurredAt()).orElse(Instant.now()));
            row.setTrialEnd(row.getCurrentPeriodEnd());
        }
    }

    private void switchPlanIfChanged(Subscription row, NormalizedBillingEvent event) {
        if (event.providerPlanId() == null) {
            return;
        }
        planMappings.findFirstByProviderAndProviderPlanId(event.provider(), event.providerPlanId())
                .filter(mapping -> !mapping.getPlanCode().equals(row.getPlan().getCode()))
                .flatMap(mapping -> plans.findByCodeWithFeatures(mapping.getPlanCode()))
                .ifPresent(plan -> {
                    log.info("Subscription {} moves from plan {} to {} per provider plan {}",
                            row.getId(), row.getPlan().getCode(), plan.getCode(), event.providerPlanId());
                    row.setPlan(plan);
                });
    }

    /**
     * A gateway subscription that becomes live supersedes any other live row of the same
     * organization: the manual or trial one it replaces, or an older provider row. The active
     * lookup expects one live row per organization, and the gateway's is the paid one.
     */
    private void supersede(Subscription live) {
        for (Subscription other : subscriptions.findByOrganizationIdAndStatusIn(live.getOrganizationId(), LIVE_STATUSES)) {
            if (other.getId().equals(live.getId())) {
                continue;
            }
            other.setStatus(SubscriptionStatus.CANCELED);
            other.setCanceledAt(Instant.now());
            other.setCancellationReason("Superseded by " + live.getProvider() + " subscription " + live.getExternalSubscriptionId());
            subscriptions.save(other);
            log.info("Subscription {} ({}) superseded by {} for organization {}",
                    other.getId(), other.getProvider(), live.getId(), live.getOrganizationId());
        }
    }

    private String projectPaymentFailed(Long organizationId, NormalizedBillingEvent event) {
        Optional<Subscription> target = event.providerSubscriptionId() != null
                ? subscriptions.findByProviderAndExternalSubscriptionId(event.provider(), event.providerSubscriptionId())
                : subscriptions.findByOrganizationIdAndStatusIn(organizationId, LIVE_STATUSES).stream()
                        .filter(s -> s.getProvider() == event.provider()).findFirst();
        if (target.isEmpty()) {
            return "payment failed for a subscription not yet projected; nothing to move";
        }
        Subscription row = target.get();
        requireSameOrganization(organizationId, row);
        if (row.getStatus() == SubscriptionStatus.ACTIVE || row.getStatus() == SubscriptionStatus.TRIALING) {
            SubscriptionStatus before = row.getStatus();
            row.setStatus(SubscriptionStatus.PAST_DUE);
            trackPastDue(row, before, SubscriptionStatus.PAST_DUE, event.occurredAt());
            subscriptions.save(row);
            return "ACTIVE -> PAST_DUE (payment failed, access kept for the grace window)";
        }
        return "payment failed while " + row.getStatus() + "; unchanged";
    }

    /**
     * A mandate event names a token, never a subscription, so the subscription it belongs to
     * is the one the mandate row already has on file (written from the subscription snapshot
     * that carried the token id, or from an earlier token event). A revocation cancels that
     * row and only that row. With nothing on file the mandate status is still recorded, the
     * event is left SKIPPED with the reason, and the sweep reconciles whatever the provider
     * has halted. It never fans out to every live row of the organization: a stale token of a
     * superseded subscription is exactly the kind that gets cancelled weeks later.
     */
    private String projectMandate(Long organizationId, NormalizedBillingEvent event) {
        if (event.mandateReference() == null) {
            return "mandate event without a reference; nothing to record";
        }
        NormalizedMandateStatus status = event.mandateStatus();
        String onFile = mandates.findByProviderAndProviderMandateRef(event.provider(), event.mandateReference())
                .map(PaymentMandate::getProviderSubscriptionId)
                .orElse(null);
        String providerSubscriptionId = onFile != null ? onFile : event.providerSubscriptionId();
        touchMandate(organizationId, event.provider(), event.mandateReference(), providerSubscriptionId,
                event.mandateMethod(), status, event.mandateMaxAmountPaise(), event.occurredAt());
        if (event.type() != NormalizedEventType.MANDATE_REVOKED) {
            return "mandate " + status;
        }
        if (providerSubscriptionId == null) {
            return SKIPPED + "mandate " + event.mandateReference() + " " + status
                    + " resolves to no subscription; recorded, reconciliation picks up a halted subscription";
        }
        Optional<Subscription> target = subscriptions.findByProviderAndExternalSubscriptionId(event.provider(), providerSubscriptionId);
        if (target.isEmpty()) {
            return SKIPPED + "mandate " + event.mandateReference() + " " + status + " names subscription "
                    + providerSubscriptionId + ", which is not projected; recorded";
        }
        Subscription row = target.get();
        requireSameOrganization(organizationId, row);
        if (!LIVE_STATUSES.contains(row.getStatus())) {
            return "mandate " + status + "; subscription " + providerSubscriptionId + " already " + row.getStatus();
        }
        row.setStatus(SubscriptionStatus.CANCELED);
        row.setCanceledAt(Optional.ofNullable(event.occurredAt()).orElse(Instant.now()));
        row.setCancellationReason("Mandate " + event.mandateReference() + " revoked");
        subscriptions.save(row);
        return "mandate " + status + "; subscription " + providerSubscriptionId + " cancelled";
    }

    /** The organization an event resolved to must own the row it is about to move. */
    private static void requireSameOrganization(Long organizationId, Subscription row) {
        if (!organizationId.equals(row.getOrganizationId())) {
            throw new BillingGatewayException("Provider subscription " + row.getExternalSubscriptionId()
                    + " belongs to organization " + row.getOrganizationId() + ", event resolved to " + organizationId);
        }
    }

    private void touchMandate(Long organizationId, ProviderId provider, String reference, String providerSubscriptionId,
                              MandateMethod method, NormalizedMandateStatus status, Long maxAmountPaise, Instant at) {
        PaymentMandate mandate = mandates.findByProviderAndProviderMandateRef(provider, reference)
                .orElseGet(() -> {
                    Organization organization = new Organization();
                    organization.setId(organizationId);
                    return PaymentMandate.builder().organization(organization).provider(provider).providerMandateRef(reference).build();
                });
        if (providerSubscriptionId != null) {
            mandate.setProviderSubscriptionId(providerSubscriptionId);
        }
        if (method != null && method != MandateMethod.UNKNOWN) {
            mandate.setMethod(method);
        }
        if (maxAmountPaise != null) {
            mandate.setMaxAmountPaise(maxAmountPaise);
        }
        if (status != null) {
            mandate.setStatus(status);
            Instant when = Optional.ofNullable(at).orElse(Instant.now());
            if (status == NormalizedMandateStatus.AUTHORIZED && mandate.getAuthorizedAt() == null) {
                mandate.setAuthorizedAt(when);
            }
            if (status == NormalizedMandateStatus.REVOKED || status == NormalizedMandateStatus.EXPIRED) {
                mandate.setRevokedAt(when);
            }
        }
        mandates.save(mandate);
    }
}
