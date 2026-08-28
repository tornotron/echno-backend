package org.tornotron.echno_backend.common.multitenancy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.TenantIdMissingException;

import java.util.function.Supplier;

/**
 * Runs a block of work under an explicit tenant, and restores whatever tenant state was
 * there before. This is the entry point for anything that executes outside a request:
 * event listeners, scheduled tasks, queue workers.
 *
 * <p>It exists because both tenant-isolation mechanisms fail <em>open</em> on a missing
 * tenant context, so forgetting to set one is silent rather than loud:
 *
 * <ul>
 *   <li>{@link HibernateFilterConfig} enables the {@code orgFilter} only when
 *       {@link TenantContext#getCurrentOrgId()} is non-null. With no org id it logs at
 *       debug and lets the transaction run unfiltered.</li>
 *   <li>{@link TenantIsolationLoadListener} returns early on the same condition, so the
 *       load-boundary check that exists to catch what the filter misses shares the
 *       filter's blind spot exactly.</li>
 * </ul>
 *
 * <p>Background work that skipped the context would therefore read every organization's
 * rows and look entirely healthy doing it. {@link #callForTenant} refuses a null org id
 * instead, which turns that silent leak into a failure at the call site.
 *
 * <p>{@link UnscopedAccessGuard} has since closed the wider hole (#507), so an undeclared
 * scope is refused at the load boundary rather than ignored. This runner is still the entry
 * point background work should use, and is the earlier and better failure of the two: it
 * names the missing organization before any work runs, rather than at whatever row happens
 * to be read first. Work that belongs to no organization says so with {@link WithoutTenant}
 * instead, which is a different statement and a much weaker one.
 *
 * <p>{@link TenantContext} is backed by {@link ThreadLocal}s, so two further things matter
 * on a pooled thread and both are handled here. The context is restored in a
 * {@code finally}, so an org id cannot leak into whatever task the executor runs next on
 * that thread. And the bypass flag is forced off for the duration, so a leaked bypass from
 * an earlier task cannot silently widen this job's reach.
 *
 * <p>Set and restore rather than set and clear: this is safe to call on a request thread,
 * where clearing would discard the context {@code TenantFilter} established for the
 * request. Where there was no previous context, restoring removes the thread-locals
 * entirely.
 *
 * @see org.tornotron.echno_backend.common.retry.TransactionalWorkRunner the
 *      transaction-boundary counterpart, which is what keeps {@code orgFilter} enabled
 *      once the context is in place
 */
@Slf4j
@Component
public class TenantScopedJobRunner {

    /**
     * Runs {@code work} with the tenant context pinned to {@code orgId} and returns its
     * result.
     *
     * @param orgId the organization the work belongs to; read from durable state such as a
     *              job row or an event payload, never inferred from the ambient thread
     * @throws TenantIdMissingException if {@code orgId} is null, rather than running the
     *                                  work unscoped
     */
    public <T> T callForTenant(Long orgId, Supplier<T> work) {
        if (orgId == null) {
            throw new TenantIdMissingException(
                    "Background work requires an explicit organization id; refusing to run with no tenant context");
        }

        Long previousOrgId = TenantContext.getCurrentOrgId();
        boolean previousBypass = TenantContext.isBypassed();
        String previousUnscopedReason = TenantContext.getUnscopedReason();

        TenantContext.setCurrentOrgId(orgId);
        TenantContext.setBypass(false);
        // An unscoped declaration inherited from an enclosing method, or left on a pooled thread,
        // would sit alongside a real organization id and say the opposite of what is true here.
        // The id already wins wherever the two are read together, so this is about the state not
        // lying rather than about what the isolation mechanisms do with it.
        TenantContext.clearUnscoped();
        try {
            return work.get();
        } finally {
            TenantContext.clear();
            if (previousOrgId != null) {
                TenantContext.setCurrentOrgId(previousOrgId);
            }
            if (previousBypass) {
                TenantContext.setBypass(true);
            }
            if (previousUnscopedReason != null) {
                TenantContext.declareUnscoped(previousUnscopedReason);
            }
        }
    }

    /** {@link #callForTenant} for work that returns nothing. */
    public void runForTenant(Long orgId, Runnable work) {
        callForTenant(orgId, () -> {
            work.run();
            return null;
        });
    }
}
