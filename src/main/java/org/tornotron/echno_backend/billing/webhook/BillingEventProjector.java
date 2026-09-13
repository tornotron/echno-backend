package org.tornotron.echno_backend.billing.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.billing.gateway.BillingEvent;
import org.tornotron.echno_backend.billing.gateway.BillingEventStatus;
import org.tornotron.echno_backend.billing.gateway.BillingGateway;
import org.tornotron.echno_backend.billing.gateway.NormalizedEventType;
import org.tornotron.echno_backend.billing.gateway.dto.NormalizedBillingEvent;
import org.tornotron.echno_backend.billing.repositories.BillingCustomerRepository;
import org.tornotron.echno_backend.billing.repositories.BillingEventRepository;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.common.multitenancy.WithoutTenant;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Turns inbox rows into entitlement: parses the stored payload again (from the verified bytes,
 * never from anything else), resolves the organization, drops what the watermark says is
 * stale, hands the rest to {@link EntitlementProjection}, and records the outcome on the row.
 *
 * <p>Order tolerance is a watermark per provider subscription: the latest provider timestamp
 * already applied. An event older than that is {@code SKIPPED}, so a {@code charged} that
 * arrived before its {@code activated} keeps the projection at ACTIVE with the charged period
 * when the late {@code activated} shows up. Equal timestamps apply, because Razorpay stamps
 * to the second and a burst can share one.
 *
 * <p>Runs with no organization in context, by declaration: the row is global and the
 * organization is something it works out from the payload or the customer mapping. Once
 * known, the projection is applied pinned to that organization.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingEventProjector {

    /** After this many failed attempts the row is left FAILED for inspection rather than retried. */
    public static final int MAX_ATTEMPTS = 5;

    private final BillingEventRepository events;
    private final BillingGateway gateway;
    private final BillingCustomerRepository customers;
    private final SubscriptionRepository subscriptions;
    private final EntitlementProjection projection;

    /**
     * Projects one inbox row. Safe to call again on a row that already succeeded (it is left
     * alone) or failed (it is retried until {@link #MAX_ATTEMPTS}).
     *
     * @param billingEventId The inbox row.
     */
    @WithoutTenant("Webhook events arrive with no session; the organization is resolved from the payload or the customer mapping")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(Long billingEventId) {
        BillingEvent row = events.findByIdForUpdate(billingEventId).orElse(null);
        if (row == null) {
            log.warn("Billing event {} vanished before projection", billingEventId);
            return;
        }
        if (row.getStatus() == BillingEventStatus.PROCESSED || row.getStatus() == BillingEventStatus.SKIPPED) {
            return;
        }
        if (row.getAttemptCount() >= MAX_ATTEMPTS) {
            log.warn("Billing event {} has exhausted {} attempts; leaving FAILED for inspection", row.getId(), MAX_ATTEMPTS);
            return;
        }
        row.setAttemptCount(row.getAttemptCount() + 1);
        try {
            String outcome = projectRow(row);
            row.setStatus(outcome == null ? BillingEventStatus.SKIPPED : BillingEventStatus.PROCESSED);
            row.setProcessedAt(Instant.now());
            row.setLastAppliedAt(outcome == null ? null : row.getOccurredAt());
            row.setLastError(outcome == null ? row.getLastError() : null);
            events.save(row);
        } catch (RuntimeException e) {
            log.error("Billing event {} ({}) failed on attempt {}: {}", row.getId(), row.getEventType(), row.getAttemptCount(), e.getMessage(), e);
            row.setStatus(BillingEventStatus.FAILED);
            row.setLastError(truncate(e.getMessage()));
            events.save(row);
        }
    }

    /** Returns the outcome line, or null when the row is to be marked SKIPPED. */
    private String projectRow(BillingEvent row) {
        List<NormalizedBillingEvent> parsed = gateway.parseEvents(row.getPayload().getBytes(StandardCharsets.UTF_8));
        if (parsed.isEmpty()) {
            row.setLastError("No events in the payload under the configured gateway");
            return null;
        }
        StringBuilder outcomes = new StringBuilder();
        boolean applied = false;
        for (NormalizedBillingEvent event : parsed) {
            if (event.type() == NormalizedEventType.IGNORED && event.mandateStatus() == null) {
                row.setLastError("Event type has no projection");
                continue;
            }
            Long organizationId = resolveOrganization(event);
            if (organizationId == null) {
                row.setLastError("Organization could not be resolved from notes, customer mapping or subscription id");
                continue;
            }
            row.setOrganizationId(organizationId);
            if (event.providerSubscriptionId() != null) {
                row.setProviderSubscriptionId(event.providerSubscriptionId());
            }
            if (isStale(event)) {
                row.setLastError("Stale: a later event for " + event.providerSubscriptionId() + " was already applied");
                continue;
            }
            outcomes.append(projection.apply(organizationId, event)).append("; ");
            applied = true;
        }
        return applied ? outcomes.toString().trim() : null;
    }

    private boolean isStale(NormalizedBillingEvent event) {
        if (event.providerSubscriptionId() == null || event.occurredAt() == null) {
            return false;
        }
        Optional<Instant> watermark = events.findLastAppliedAt(event.provider(), event.providerSubscriptionId());
        return watermark.isPresent() && event.occurredAt().isBefore(watermark.get());
    }

    private Long resolveOrganization(NormalizedBillingEvent event) {
        if (event.organizationId() != null) {
            return event.organizationId();
        }
        if (event.providerCustomerId() != null) {
            Optional<Long> byCustomer = customers.findByProviderAndProviderCustomerId(event.provider(), event.providerCustomerId())
                    .map(customer -> customer.getOrganization().getId());
            if (byCustomer.isPresent()) {
                return byCustomer.get();
            }
        }
        if (event.providerSubscriptionId() != null) {
            return subscriptions.findByProviderAndExternalSubscriptionId(event.provider(), event.providerSubscriptionId())
                    .map(subscription -> subscription.getOrganizationId())
                    .orElse(null);
        }
        return null;
    }

    private static String truncate(String message) {
        if (message == null) {
            return "(no message)";
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }
}
