package org.tornotron.echno_backend.billing.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.checkout.CheckoutRequiredException;
import org.tornotron.echno_backend.billing.checkout.CheckoutService;
import org.tornotron.echno_backend.billing.dto.CheckoutSessionCreateDto;
import org.tornotron.echno_backend.billing.dto.CheckoutSessionDto;
import org.tornotron.echno_backend.billing.dto.SubscriptionDto;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;
import org.tornotron.echno_backend.billing.gateway.BillingGateway;
import org.tornotron.echno_backend.billing.gateway.BillingGatewayProperties;
import org.tornotron.echno_backend.billing.gateway.BillingNotConfiguredException;
import org.tornotron.echno_backend.billing.gateway.MandatePolicy;
import org.tornotron.echno_backend.billing.gateway.NormalizedSubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.gateway.dto.ChangePlanCommand;
import org.tornotron.echno_backend.billing.gateway.dto.GatewaySubscription;
import org.tornotron.echno_backend.billing.gateway.dto.NormalizedBillingEvent;
import org.tornotron.echno_backend.billing.repositories.CheckoutSessionRepository;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;
import org.tornotron.echno_backend.billing.webhook.BillingReconciliationService;
import org.tornotron.echno_backend.billing.webhook.EntitlementProjection;
import org.tornotron.echno_backend.common.exception.NoActiveSubscriptionException;
import org.tornotron.echno_backend.common.exception.PlanNotFoundException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The subscription lifecycle as the organization drives it: subscribe, change plan, cancel.
 * Decides per call whether the row is written directly or through the payment provider.
 *
 * <p>A row a provider backs is only ever moved by the provider's answer. Change-plan and
 * cancel go to the port first and the projection then applies what came back, exactly as it
 * would for the webhook; nothing here writes a lifecycle field of such a row. A cancel that
 * reaches the provider is also stamped on the row, so a charge the provider reports afterwards
 * is recorded but never re-activates it.
 *
 * <p>Without a provider ({@code echno.billing.provider=none}) the direct path stays, which is
 * how staging runs without keys, but the self-service endpoints are limited to free plans
 * unless {@code echno.billing.allow-manual-paid} is on. With a provider, a paid plan asked for
 * through the self-service create goes through the checkout: the provider subscription is
 * created and the INCOMPLETE projection row is returned; the entitlement follows once the
 * buyer authorizes. The platform-admin create stays the manual-grant path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionLifecycleService {

    private final SubscriptionService subscriptions;
    private final CheckoutService checkout;
    private final BillingGateway gateway;
    private final BillingGatewayProperties properties;
    private final EntitlementProjection projection;
    private final PlanRepository plans;
    private final CheckoutSessionRepository sessions;

    /** Self-service subscribe for the organization's own admin. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SubscriptionDto subscribe(Long organizationId, Long userId, String planCode, BillingPeriod billingPeriod) {
        BillingPeriod period = Optional.ofNullable(billingPeriod).orElse(BillingPeriod.MONTHLY);
        Plan plan = plans.findByCodeWithFeatures(planCode)
                .orElseThrow(() -> new PlanNotFoundException("Plan with code '" + planCode + "' was not found"));
        if (MandatePolicy.cycleAmountPaise(plan, period) <= 0) {
            return subscriptions.createSubscription(organizationId, userId, plan.getCode(), period);
        }
        if (gateway.isEnabled()) {
            CheckoutSessionDto session = checkout.createSession(organizationId, userId, CheckoutSessionCreateDto.builder()
                    .planCode(plan.getCode()).billingPeriod(period).build());
            log.info("Self-service subscribe for organization {} on paid plan {} opened checkout session {}",
                    organizationId, plan.getCode(), session.getId());
            return subscriptions.getProviderSubscription(gateway.providerId(), session.getProviderSubscriptionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Checkout session " + session.getId() + " opened with no projection row for " + session.getProviderSubscriptionId()));
        }
        if (properties.isAllowManualPaid()) {
            return subscriptions.createSubscription(organizationId, userId, plan.getCode(), period);
        }
        throw new BillingNotConfiguredException();
    }

    /**
     * Moves the organization's live subscription to another plan.
     *
     * @param selfService Whether the organization's own admin is asking, in which case a paid
     *                    plan on a manual row is refused unless manual paid rows are allowed.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SubscriptionDto changePlan(Long organizationId, String newPlanCode, boolean acceptPerChargeAfa, boolean selfService) {
        SubscriptionDto current = subscriptions.getActiveSubscription(organizationId)
                .orElseThrow(() -> new NoActiveSubscriptionException("Organization " + organizationId + " has no active subscription"));
        Plan newPlan = plans.findByCodeAndIsActiveTrue(newPlanCode)
                .orElseThrow(() -> new PlanNotFoundException("Active plan with code '" + newPlanCode + "' was not found"));
        if (!providerBacked(current)) {
            BillingPeriod period = periodOf(current);
            if (selfService && MandatePolicy.cycleAmountPaise(newPlan, period) > 0 && !properties.isAllowManualPaid()) {
                if (gateway.isEnabled()) {
                    throw new CheckoutRequiredException("Plan '" + newPlan.getCode() + "' is paid; open a checkout for it "
                            + "(POST /api/v1/billing/checkout/web/sessions). The current subscription is superseded once the new one activates");
                }
                throw new BillingNotConfiguredException();
            }
            return subscriptions.changeSubscription(organizationId, newPlan.getCode());
        }
        if (!gateway.isEnabled()) {
            throw new BillingNotConfiguredException();
        }
        ProviderId provider = gateway.providerId();
        BillingPeriod period = periodOf(current);
        gateway.ensurePlan(newPlan, period);
        GatewaySubscription answer = gateway.changePlan(new ChangePlanCommand(organizationId,
                current.getProviderSubscriptionId(), newPlan.getCode(), period, 1, false, acceptPerChargeAfa));
        String outcome = projection.apply(organizationId, synthetic(provider, "change-plan", organizationId, current, answer));
        log.info("Organization {} asked to move {} to plan {}; provider answered {}: {}",
                organizationId, current.getProviderSubscriptionId(), newPlan.getCode(), answer.status(), outcome);
        return subscriptions.getProviderSubscription(provider, current.getProviderSubscriptionId())
                .orElseThrow(() -> new IllegalStateException("Projection row missing after change-plan of " + current.getProviderSubscriptionId()));
    }

    /** Cancels the organization's live subscription, now or at the end of the current period. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void cancel(Long organizationId, boolean immediate) {
        SubscriptionDto current = subscriptions.getActiveSubscription(organizationId)
                .orElseThrow(() -> new NoActiveSubscriptionException("Organization " + organizationId + " has no active subscription"));
        if (!providerBacked(current)) {
            subscriptions.cancelSubscription(organizationId, immediate);
            return;
        }
        if (!gateway.isEnabled()) {
            throw new BillingNotConfiguredException();
        }
        ProviderId provider = gateway.providerId();
        GatewaySubscription answer = gateway.cancelSubscription(current.getProviderSubscriptionId(), !immediate);
        boolean endsNow = answer.status() == NormalizedSubscriptionStatus.CANCELLED
                || answer.status() == NormalizedSubscriptionStatus.COMPLETED;
        subscriptions.markCancelRequested(provider, current.getProviderSubscriptionId(), !endsNow);
        String outcome = projection.apply(organizationId, synthetic(provider, "cancel", organizationId, current, answer));
        log.info("Organization {} cancelled {} (immediate {}); provider answered {}: {}",
                organizationId, current.getProviderSubscriptionId(), immediate, answer.status(), outcome);
    }

    private static boolean providerBacked(SubscriptionDto row) {
        return row.getProviderSubscriptionId() != null && row.getProvider() != null
                && !ProviderId.MANUAL.name().equals(row.getProvider()) && !"NONE".equals(row.getProvider());
    }

    /**
     * The billing period a row was bought on: the checkout session that opened it knows, and
     * a manual row is read off its period length.
     */
    private BillingPeriod periodOf(SubscriptionDto row) {
        if (row.getProviderSubscriptionId() != null) {
            Optional<BillingPeriod> fromSession = sessions
                    .findFirstByProviderAndProviderSubscriptionId(ProviderId.valueOf(row.getProvider()), row.getProviderSubscriptionId())
                    .map(session -> session.getBillingPeriod());
            if (fromSession.isPresent()) {
                return fromSession.get();
            }
        }
        if (row.getCurrentPeriodStart() != null && row.getCurrentPeriodEnd() != null
                && Duration.between(row.getCurrentPeriodStart(), row.getCurrentPeriodEnd()).toDays() > 100) {
            return BillingPeriod.ANNUAL;
        }
        return BillingPeriod.MONTHLY;
    }

    /** The provider's answer as the event the webhook would carry, so the projection treats both alike. */
    private static NormalizedBillingEvent synthetic(ProviderId provider, String action, Long organizationId,
                                                    SubscriptionDto row, GatewaySubscription answer) {
        return new NormalizedBillingEvent(provider,
                action + "-" + row.getProviderSubscriptionId() + "-" + Instant.now().toEpochMilli(),
                BillingReconciliationService.eventTypeFor(answer), Instant.now(), organizationId,
                row.getProviderSubscriptionId(), answer.providerCustomerId(), answer.providerPlanId(),
                row.getPlan() == null ? null : row.getPlan().getCode(), answer, answer.mandateReference(), null, null, null);
    }
}
