package org.tornotron.echno_backend.common.multitenancy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.TenantIdMissingException;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;

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
 * <h2>The tenant is not the filter</h2>
 *
 * <p>Pinning the organization id does not by itself turn the {@code orgFilter} on.
 * {@link HibernateFilterConfig} enables it when a {@code @Transactional} method in this
 * codebase is entered, on the session that transaction binds. A repository called with no
 * transaction open gets a session of its own for that one call, nobody enables the filter on
 * it, and a query returning scalars or projections never reaches the load listener either. So
 * work that reads the database directly under {@link #callForTenant} reads every organization's
 * rows. That was #877: the toolbox-talk reminder for one organization listed another's projects.
 * Declaring {@code @Transactional(readOnly = true)} on the repository methods does not help,
 * because the aspect only advises our own classes and a repository's proxy is not one.
 *
 * <p>So there are two entry points, and the choice between them is about who owns the
 * transaction:
 *
 * <ul>
 *   <li>{@link #callForTenantInTransaction} pins the tenant and then opens one transaction
 *       around the work, through {@link TransactionalWorkRunner} so the aspect sees the tenant
 *       and enables the filter. Use it whenever the work reads or writes through repositories
 *       itself. If a transaction is already open the work joins it, and the aspect enables the
 *       filter for this organization on the joined session.</li>
 *   <li>{@link #callForTenant} pins the tenant and nothing else. It is for work that draws its
 *       own transaction boundaries: {@code TransactionRetryTemplate} (which declines to retry
 *       inside a transaction it did not open), batches committed one at a time, calls to an
 *       outside service that must not hold a transaction open, or exceptions caught per item
 *       that would otherwise mark one shared transaction rollback-only. Everything it touches in
 *       the database has to sit behind one of those boundaries or behind a
 *       {@code @Transactional} method on another bean.</li>
 * </ul>
 *
 * <p>The runner does not open a transaction for every caller because several of the second
 * kind would break inside one: the compliance worker would lose its retries and hold a
 * transaction across the AI call, and the low-stock sweep's latch race would poison the
 * project's whole pass. {@code TenantJobTransactionBoundaryTest} holds the second kind to its
 * contract instead: work handed to {@link #callForTenant} may not reach a repository except
 * through a transaction boundary.
 *
 * @see org.tornotron.echno_backend.common.retry.TransactionalWorkRunner the
 *      transaction-boundary counterpart, which is what keeps {@code orgFilter} enabled
 *      once the context is in place
 */
@Slf4j
@Component
public class TenantScopedJobRunner {

    private final TransactionalWorkRunner transactions;

    public TenantScopedJobRunner(TransactionalWorkRunner transactions) {
        this.transactions = transactions;
    }

    /**
     * Runs {@code work} with the tenant context pinned to {@code orgId}, inside one
     * transaction opened after the tenant is in place, and returns its result.
     *
     * <p>The order is what makes the filter work. The tenant is set first, and the
     * transaction is then opened through a proxied {@code @Transactional} call, so
     * {@link HibernateFilterConfig} reads this organization on the way in and enables the
     * {@code orgFilter} on the session the transaction binds.
     *
     * @param orgId the organization the work belongs to; read from durable state such as a
     *              job row or an event payload, never inferred from the ambient thread
     * @throws TenantIdMissingException if {@code orgId} is null, rather than running the
     *                                  work unscoped
     */
    public <T> T callForTenantInTransaction(Long orgId, Supplier<T> work) {
        return callForTenant(orgId, () -> transactions.runInTransaction(work));
    }

    /** {@link #callForTenantInTransaction} for work that returns nothing. */
    public void runForTenantInTransaction(Long orgId, Runnable work) {
        callForTenantInTransaction(orgId, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Runs {@code work} with the tenant context pinned to {@code orgId} and returns its
     * result. No transaction is opened: see the class comment for when that is right, and use
     * {@link #callForTenantInTransaction} otherwise.
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
