package org.tornotron.echno_backend.common.multitenancy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.UnscopedTenantAccessException;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The single place that decides what happens when work reaches the database with no tenant scope
 * declared: no organization id, no bypass, no {@link WithoutTenant}.
 *
 * <p>Both isolation mechanisms used to answer that question by doing nothing. {@link
 * HibernateFilterConfig} skipped the {@code orgFilter} and logged at debug;
 * {@link TenantIsolationLoadListener} returned early on the same condition. They read as defence
 * in depth and were not: they cover different things (query shape against primary-key loads) but
 * gave up on identical input, so a null scope removed isolation rather than narrowing it, and
 * left nothing in the log to distinguish that from a correct run.
 *
 * <p>Both now call in here, and the answer is configured per boundary, because the two boundaries
 * are not equally precise:
 *
 * <ul>
 *   <li><b>The load boundary</b> fires when a {@link TenantScopedEntity} is actually read. That is
 *       the leak itself rather than a proxy for it, so it defaults to {@code DENY}. Every path
 *       that legitimately reads tenant-scoped rows outside a tenant has been enumerated and now
 *       declares itself.
 *   <li><b>The transaction boundary</b> fires on every {@code @Transactional} method in the
 *       package tree, including the many that touch no tenant-scoped entity at all: users,
 *       organizations, plans, subscriptions. Denying there would refuse work that was never at
 *       risk, so it defaults to {@code WARN}. The counter and the log line below are what turn
 *       the remaining call sites into a list instead of a guess, which is the step that has to
 *       come before that default can move.
 * </ul>
 *
 * <p>Either can be moved with {@code echno.multitenancy.load-boundary} and
 * {@code echno.multitenancy.transaction-boundary}, so a deployment can tighten ahead of the
 * default or step back during an incident without waiting on a code change.
 *
 * <p>The WARN is rate limited per call site. An unscoped read inside a loop would otherwise
 * produce one line per row and bury the very thing it is reporting.
 */
@Component
public class UnscopedAccessGuard {

    private static final Logger logger = LoggerFactory.getLogger(UnscopedAccessGuard.class);

    /**
     * Counter for every unscoped access, tagged with the boundary that saw it, the call site, and
     * the policy in force. Never rate limited: the log is sampled, the count is not, so a
     * dashboard reports the true volume.
     */
    private static final String METRIC = "echno.multitenancy.unscoped";

    /**
     * Caps the rate-limiter map. A call site that arrives after the cap is logged every time
     * rather than tracked, which is noisier than the alternative but never silent, and the cap is
     * far above the number of distinct entity types and transactional methods the application has.
     */
    private static final int MAX_TRACKED_CALL_SITES = 2_000;

    private final MeterRegistry meterRegistry;
    private final UnscopedAccessPolicy loadBoundaryPolicy;
    private final UnscopedAccessPolicy transactionBoundaryPolicy;
    private final long warnIntervalNanos;
    private final Map<String, AtomicLong> lastWarnedNanos = new ConcurrentHashMap<>();

    public UnscopedAccessGuard(
            MeterRegistry meterRegistry,
            @Value("${echno.multitenancy.load-boundary:DENY}") String loadBoundary,
            @Value("${echno.multitenancy.transaction-boundary:WARN}") String transactionBoundary,
            @Value("${echno.multitenancy.warn-interval-seconds:60}") long warnIntervalSeconds) {
        this.meterRegistry = meterRegistry;
        this.loadBoundaryPolicy =
                UnscopedAccessPolicy.parse("echno.multitenancy.load-boundary", loadBoundary);
        this.transactionBoundaryPolicy =
                UnscopedAccessPolicy.parse("echno.multitenancy.transaction-boundary", transactionBoundary);
        this.warnIntervalNanos = Duration.ofSeconds(Math.max(0, warnIntervalSeconds)).toNanos();
    }

    public UnscopedAccessPolicy getLoadBoundaryPolicy() {
        return loadBoundaryPolicy;
    }

    public UnscopedAccessPolicy getTransactionBoundaryPolicy() {
        return transactionBoundaryPolicy;
    }

    /**
     * Called when a {@link TenantScopedEntity} has been loaded and no scope is declared.
     *
     * @param entityType the entity that was read, which is the useful half of the report: it says
     *                   what data was exposed, not merely that something was
     * @throws UnscopedTenantAccessException under {@code DENY}
     */
    public void onUnscopedLoad(Class<?> entityType) {
        act(Boundary.LOAD, loadBoundaryPolicy, entityType.getSimpleName(),
                () -> "Tenant-scoped entity " + entityType.getSimpleName()
                        + " was loaded with no tenant scope declared");
    }

    /**
     * Called when a {@code @Transactional} method is entered and no scope is declared, so the
     * transaction is about to run with the {@code orgFilter} off.
     *
     * @param callSite the advised method, so the log names something greppable
     * @throws UnscopedTenantAccessException under {@code DENY}
     */
    public void onUnscopedTransaction(String callSite) {
        act(Boundary.TRANSACTION, transactionBoundaryPolicy, callSite,
                () -> "Transaction " + callSite
                        + " was entered with no tenant scope declared, so orgFilter is off for it");
    }

    private void act(Boundary boundary, UnscopedAccessPolicy policy, String callSite,
                     Supplier<String> message) {
        if (policy == UnscopedAccessPolicy.ALLOW) {
            return;
        }

        Counter.builder(METRIC)
                .tag("boundary", boundary.tag)
                .tag("call_site", callSite)
                .tag("policy", policy.name())
                .description("Work that reached tenant-scoped data with no tenant scope declared")
                .register(meterRegistry)
                .increment();

        if (policy == UnscopedAccessPolicy.DENY) {
            String detail = message.get();
            // Not rate limited: a denial is one event that ended the work, not a stream, and it
            // is the line whoever is holding the incident needs in full.
            logger.error("[TenantScope] {}. Refused under {}={}.",
                    detail, boundary.property, policy);
            throw new UnscopedTenantAccessException(detail);
        }

        if (shouldWarn(boundary, callSite)) {
            logger.warn("[TenantScope] {}. Allowed under {}={}; declare the scope with an "
                            + "organization id, TenantScopedJobRunner, @WithoutTenant or "
                            + "@BypassTenantFilter. Further reports for this call site are "
                            + "suppressed briefly; the {} counter is not.",
                    message.get(), boundary.property, policy, METRIC);
        }
    }

    /**
     * One WARN per call site per interval. Racing threads may both win the compare-and-set window
     * and log twice, which is a better trade than a lock on a path that exists to observe.
     */
    private boolean shouldWarn(Boundary boundary, String callSite) {
        if (warnIntervalNanos == 0) {
            return true;
        }
        String key = boundary.tag + '|' + callSite;
        long now = System.nanoTime();

        AtomicLong last = lastWarnedNanos.get(key);
        if (last == null) {
            if (lastWarnedNanos.size() >= MAX_TRACKED_CALL_SITES) {
                return true;
            }
            AtomicLong existing = lastWarnedNanos.putIfAbsent(key, new AtomicLong(now));
            if (existing == null) {
                // First sighting of this call site, which is the report that matters most.
                return true;
            }
            last = existing;
        }

        long previous = last.get();
        // Subtraction rather than comparison, because nanoTime has an arbitrary origin and wraps;
        // the difference stays correct across the wrap where a direct comparison would not.
        if (now - previous < warnIntervalNanos) {
            return false;
        }
        return last.compareAndSet(previous, now);
    }

    private enum Boundary {
        LOAD("load", "echno.multitenancy.load-boundary"),
        TRANSACTION("transaction", "echno.multitenancy.transaction-boundary");

        private final String tag;
        private final String property;

        Boundary(String tag, String property) {
            this.tag = tag;
            this.property = property;
        }
    }
}
