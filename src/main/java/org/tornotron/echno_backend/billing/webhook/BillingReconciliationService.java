package org.tornotron.echno_backend.billing.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.billing.Subscription;
import org.tornotron.echno_backend.billing.repositories.CheckoutSessionRepository;
import org.tornotron.echno_backend.billing.gateway.NormalizedSubscriptionStatus;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.checkout.CheckoutSessionStatus;
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
import java.time.temporal.ChronoUnit;
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
    private final CheckoutSessionRepository sessions;

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
        if (row.getStatus() == SubscriptionStatus.CANCELED && row.getCancelRequestedAt() != null && isLive(current.status())) {
            // The organization cancelled and the provider still reports it live: the cancel is
            // re-sent, never the other way round. The row stays CANCELED whatever comes back.
            log.warn("Provider still reports {} for {} cancelled by organization {} on {}; re-sending the cancel",
                    current.status(), providerSubscriptionId, row.getOrganizationId(), row.getCancelRequestedAt());
            current = gateway.cancelSubscription(providerSubscriptionId, false);
        } else if (row.getStatus() == SubscriptionStatus.INCOMPLETE && !isLive(current.status())
                && current.status().toSubscriptionStatus() == SubscriptionStatus.INCOMPLETE && abandoned(row)) {
            // Past its checkout window and never authorized: an abandoned checkout. Without
            // this the row would be re-read from the provider every hour for good.
            log.info("Checkout for {} (organization {}) was never authorized within its window; expiring it",
                    providerSubscriptionId, row.getOrganizationId());
            current = new GatewaySubscription(current.providerSubscriptionId(), current.providerPlanId(),
                    current.providerCustomerId(), NormalizedSubscriptionStatus.EXPIRED_BEFORE_AUTH,
                    current.currentPeriodStart(), current.currentPeriodEnd(), current.nextChargeAt(),
                    current.mandateReference(), null);
            expireOpenSession(row);
        }
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
            try {
                projector.process(row.getId());
            } catch (RuntimeException e) {
                // process() records a projection failure on the row itself; this catches a
                // failure of that recording, so one bad row does not end the batch.
                log.error("Billing event {} could not be retried: {}", row.getId(), e.getMessage(), e);
            }
            attempted++;
        }
        return attempted;
    }

    /** Whether the provider is still entitled to debit, or about to be. */
    private static boolean isLive(NormalizedSubscriptionStatus status) {
        return switch (status) {
            case TRIAL, AUTHENTICATED, ACTIVE, PAYMENT_FAILED_RETRYING, PAUSED -> true;
            default -> false;
        };
    }

    /** Past the checkout session's expiry, or, with no session on file, a day old. */
    private boolean abandoned(Subscription row) {
        Instant now = Instant.now();
        return sessions.findFirstByProviderAndProviderSubscriptionId(row.getProvider(), row.getExternalSubscriptionId())
                .map(session -> session.getExpiresAt() != null && session.getExpiresAt().isBefore(now))
                .orElseGet(() -> row.getCreatedAt() != null && row.getCreatedAt().plus(1, ChronoUnit.DAYS).isBefore(now));
    }

    private void expireOpenSession(Subscription row) {
        sessions.findFirstByProviderAndProviderSubscriptionId(row.getProvider(), row.getExternalSubscriptionId())
                .filter(session -> session.getStatus() == CheckoutSessionStatus.OPEN)
                .ifPresent(session -> {
                    session.setStatus(CheckoutSessionStatus.EXPIRED);
                    sessions.save(session);
                });
    }

    /** The event type a provider snapshot stands for, so a synthetic event reads like the webhook would. */
    public static NormalizedEventType eventTypeFor(GatewaySubscription current) {
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
