package org.tornotron.echno_backend.billing.reconcile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.billing.Subscription;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.BillingGateway;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.billing.webhook.BillingReconciliationService;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.multitenancy.WithoutTenant;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The scheduled half of the reconciliation fallback (spec section 5.4). Webhooks are the
 * source of truth but can be missed, so once an hour this asks the provider about every
 * provider-backed subscription that looks stale and projects the answer, which turns a
 * missed webhook into a delay rather than a permanently wrong entitlement. It then hands
 * the inbox rows still waiting or failed back to the projector, up to its attempt bound.
 *
 * <p>Stale means: a live row (ACTIVE, TRIALING, PAST_DUE) whose period has ended, any row
 * in PAST_DUE at all (the provider is retrying and the outcome may have been missed), and a
 * row left INCOMPLETE longer than the configured wait. Only rows of the configured provider
 * are looked at; MANUAL rows have nothing to reconcile against.
 *
 * <p>Runs per organization through {@link TenantScopedJobRunner}, so every row it touches
 * is checked against the organization it belongs to like any other write, and one
 * organization's failure costs the others nothing. With no provider configured the pass
 * returns at once: the bean exists, the schedule fires, nothing is fetched.
 *
 * <p>{@code @Scheduled} elects no leader; on N replicas the pass runs N times. Each fetch
 * projects the provider's current truth, so a repeated pass changes nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "echno.billing.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class BillingReconciliationSweep {

    private static final List<SubscriptionStatus> LIVE =
            List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING, SubscriptionStatus.PAST_DUE);

    private final BillingGateway gateway;
    private final SubscriptionRepository subscriptions;
    private final BillingReconciliationService reconciliation;
    private final TenantScopedJobRunner tenantRunner;
    private final BillingReconcileProperties properties;

    @Scheduled(cron = "${echno.billing.reconcile.cron:0 0 * * * *}", zone = "${echno.billing.reconcile.zone:UTC}")
    @WithoutTenant("The sweep belongs to no organization; each subscription is reconciled pinned to its own")
    public void sweep() {
        try {
            runPass();
        } catch (Exception e) {
            log.error("Billing reconciliation pass failed: {}", e.getMessage(), e);
        }
    }

    /** What one pass did; package-private so a test can run it without waiting on the cron. */
    record Summary(int organizations, int reconciled, int failed, int retried) {
        static final Summary NONE = new Summary(0, 0, 0, 0);
    }

    Summary runPass() {
        if (!gateway.isEnabled()) {
            log.debug("Billing reconciliation skipped: no billing provider is configured");
            return Summary.NONE;
        }
        Instant now = Instant.now();
        Instant incompleteBefore = now.minus(Math.max(0, properties.getIncompleteAfterMinutes()), ChronoUnit.MINUTES);
        int cap = Math.max(1, properties.getMaxPerRun());
        List<Subscription> stale = subscriptions.findStaleProviderSubscriptions(
                gateway.providerId(), LIVE, now, incompleteBefore, PageRequest.of(0, cap));
        if (stale.size() == cap) {
            log.info("Billing reconciliation took its per-pass cap of {} stale subscription(s); any beyond it wait for the next pass", cap);
        }

        Map<Long, List<Subscription>> byOrganization = new LinkedHashMap<>();
        for (Subscription row : stale) {
            byOrganization.computeIfAbsent(row.getOrganizationId(), k -> new java.util.ArrayList<>()).add(row);
        }

        int reconciled = 0;
        int failed = 0;
        for (Map.Entry<Long, List<Subscription>> entry : byOrganization.entrySet()) {
            Long organizationId = entry.getKey();
            for (Subscription row : entry.getValue()) {
                try {
                    String outcome = tenantRunner.callForTenant(organizationId,
                            () -> reconciliation.reconcile(row.getExternalSubscriptionId()));
                    log.info("Billing reconciliation repaired subscription {} ({}) of organization {}: {}",
                            row.getId(), row.getExternalSubscriptionId(), organizationId, outcome);
                    reconciled++;
                } catch (Exception e) {
                    log.error("Billing reconciliation could not repair subscription {} ({}) of organization {}: {}",
                            row.getId(), row.getExternalSubscriptionId(), organizationId, e.getMessage(), e);
                    failed++;
                }
            }
        }

        int retried = reconciliation.retryPending(Math.max(1, properties.getRetryBatch()));

        Summary summary = new Summary(byOrganization.size(), reconciled, failed, retried);
        log.info("Billing reconciliation looked at {} stale subscription(s) across {} organization(s): "
                        + "repaired {}, failed {}; re-queued {} inbox row(s)",
                stale.size(), summary.organizations(), summary.reconciled(), summary.failed(), summary.retried());
        return summary;
    }
}
