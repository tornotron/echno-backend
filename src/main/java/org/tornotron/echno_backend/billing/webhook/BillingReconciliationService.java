package org.tornotron.echno_backend.billing.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.billing.Subscription;
import org.tornotron.echno_backend.billing.gateway.BillingEvent;
import org.tornotron.echno_backend.billing.gateway.BillingEventStatus;
import org.tornotron.echno_backend.billing.gateway.BillingGateway;
import org.tornotron.echno_backend.billing.gateway.BillingGatewayException;
import org.tornotron.echno_backend.billing.gateway.NormalizedEventType;
import org.tornotron.echno_backend.billing.gateway.dto.GatewaySubscription;
import org.tornotron.echno_backend.billing.gateway.dto.NormalizedBillingEvent;
import org.tornotron.echno_backend.billing.repositories.BillingEventRepository;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.common.multitenancy.WithoutTenant;

import java.time.Instant;
import java.util.List;

/**
 * The reconciliation fallback of section 5.4: webhooks are the source of truth but can be
 * missed, so the provider's current state can be fetched and projected on demand. This is
 * the service method; the scheduled sweep that decides which subscriptions look stale is
 * Phase 3. Also the bounded retry over inbox rows that failed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingReconciliationService {

    private final BillingGateway gateway;
    private final SubscriptionRepository subscriptions;
    private final BillingEventRepository events;
    private final EntitlementProjection projection;
    private final BillingEventProjector projector;

    /**
     * Fetches the provider's state for one subscription and projects it as if the matching
     * event had arrived now. Authoritative: it is the provider's current truth, so the
     * watermark does not apply.
     *
     * @param providerSubscriptionId The provider's subscription id.
     * @return What the projection did.
     */
    @WithoutTenant("Reconciliation runs outside every tenant; the organization comes from the projected row")
    public String reconcile(String providerSubscriptionId) {
        if (!gateway.isEnabled()) {
            throw new BillingGatewayException("No billing provider is configured; nothing to reconcile against");
        }
        Subscription row = subscriptions.findByProviderAndExternalSubscriptionId(gateway.providerId(), providerSubscriptionId)
                .orElseThrow(() -> new BillingGatewayException(
                        "No projected subscription for provider id " + providerSubscriptionId + "; nothing to reconcile"));
        GatewaySubscription current = gateway.fetchSubscription(providerSubscriptionId);
        NormalizedBillingEvent synthetic = new NormalizedBillingEvent(
                gateway.providerId(), "reconcile-" + providerSubscriptionId + "-" + Instant.now().toEpochMilli(),
                eventTypeFor(current), Instant.now(), row.getOrganizationId(), providerSubscriptionId,
                current.providerCustomerId(), current.providerPlanId(), row.getPlan().getCode(), current,
                current.mandateReference(), null, null, null);
        String outcome = projection.apply(row.getOrganizationId(), synthetic);
        log.info("Reconciled {} for organization {}: {}", providerSubscriptionId, row.getOrganizationId(), outcome);
        return outcome;
    }

    /**
     * Retries inbox rows still RECEIVED or FAILED, oldest first, up to the projector's attempt
     * bound. The retry of section 6.3; scheduling it is Phase 3.
     *
     * @param batch How many rows to look at.
     * @return How many rows were attempted.
     */
    public int retryPending(int batch) {
        List<BillingEvent> pending = events.findByStatusInOrderByReceivedAtAsc(
                List.of(BillingEventStatus.RECEIVED, BillingEventStatus.FAILED), PageRequest.of(0, batch));
        int attempted = 0;
        for (BillingEvent row : pending) {
            if (row.getAttemptCount() >= BillingEventProjector.MAX_ATTEMPTS) {
                continue;
            }
            projector.process(row.getId());
            attempted++;
        }
        return attempted;
    }

    private static NormalizedEventType eventTypeFor(GatewaySubscription current) {
        return switch (current.status()) {
            case CREATED, PENDING_AUTH, AUTH_FAILED, EXPIRED_BEFORE_AUTH -> NormalizedEventType.SUBSCRIPTION_PENDING;
            case TRIAL, AUTHENTICATED -> NormalizedEventType.SUBSCRIPTION_AUTHENTICATED;
            case ACTIVE -> NormalizedEventType.SUBSCRIPTION_ACTIVATED;
            case PAYMENT_FAILED_RETRYING -> NormalizedEventType.SUBSCRIPTION_PENDING;
            case HALTED, DUNNING_EXHAUSTED -> NormalizedEventType.SUBSCRIPTION_HALTED;
            case PAUSED -> NormalizedEventType.SUBSCRIPTION_PAUSED;
            case CANCELLED -> NormalizedEventType.SUBSCRIPTION_CANCELLED;
            case COMPLETED -> NormalizedEventType.SUBSCRIPTION_COMPLETED;
        };
    }
}
