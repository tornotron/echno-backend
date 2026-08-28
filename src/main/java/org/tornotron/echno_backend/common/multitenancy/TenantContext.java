package org.tornotron.echno_backend.common.multitenancy;

/**
 * The tenant scope in force on the current thread. Read by {@link HibernateFilterConfig} to
 * decide whether to enable the Hibernate {@code orgFilter}, and by
 * {@link TenantIsolationLoadListener} to decide whether a loaded row belongs to the caller.
 *
 * <p>There are three ways to declare a scope, and declaring one of them is mandatory for any
 * work that reaches a {@link TenantScopedEntity}:
 *
 * <ul>
 *   <li><b>An organization id.</b> The ordinary case. {@code TenantFilter} sets it from the
 *       request, {@link TenantScopedJobRunner} sets it from durable state for background work.
 *   <li><b>Bypass</b>, via {@code @BypassTenantFilter} or the global-admin branch of
 *       {@code TenantFilter}. Means "read across organizations on purpose". It is the widest
 *       declaration and the one that is audited at WARN where it is set.
 *   <li><b>Unscoped</b>, via {@link WithoutTenant} or {@link #declareUnscoped}. Means "this work
 *       belongs to no organization". It suppresses the missing-scope failure and nothing else:
 *       where an organization id is also present, isolation stays fully in force. That is what
 *       separates it from bypass, and it is why the annotation is safe to put on a method that
 *       may also be called inside a tenant request.
 * </ul>
 *
 * <p>None of the three is the same as the fourth state, which is no declaration at all. Before
 * #507 that state read as "no tenant", both mechanisms above gave up on it, and the work ran
 * across every organization with nothing in the logs to say so. It now means "somebody forgot",
 * and {@link UnscopedAccessGuard} decides what happens.
 *
 * <p>Every field is a {@link ThreadLocal}, so nothing here crosses an {@code @Async} hop or a
 * thread pool: there is no {@code TaskDecorator} propagating it. Whoever owns a thread owns
 * clearing it, and both {@code TenantFilter} and {@link TenantScopedJobRunner} do that in a
 * {@code finally}.
 */
public final class TenantContext {

    private static final ThreadLocal<Long> currentOrgId = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> bypassed = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<String> unscopedReason = new ThreadLocal<>();

    private TenantContext() {}

    public static void setCurrentOrgId(Long orgId) {
        currentOrgId.set(orgId);
    }

    public static Long getCurrentOrgId() {
        return currentOrgId.get();
    }

    public static void setBypass(boolean bypass) {
        bypassed.set(bypass);
    }

    public static boolean isBypassed() {
        return Boolean.TRUE.equals(bypassed.get());
    }

    /**
     * Declares that the work on this thread belongs to no organization, so the absence of an
     * organization id is a decision rather than an oversight.
     *
     * @param reason why this work has no tenant, recorded so the declaration can be audited and
     *               so a stale one can be traced back to whoever set it. Required.
     */
    public static void declareUnscoped(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "An unscoped declaration needs a reason; an unexplained one is what this replaces");
        }
        unscopedReason.set(reason);
    }

    /** The reason passed to {@link #declareUnscoped}, or null if nothing was declared. */
    public static String getUnscopedReason() {
        return unscopedReason.get();
    }

    public static boolean isUnscopedDeclared() {
        return unscopedReason.get() != null;
    }

    /** Withdraws an unscoped declaration, leaving the organization id and bypass flag alone. */
    public static void clearUnscoped() {
        unscopedReason.remove();
    }

    /**
     * Whether any of the three scopes is declared. False means the thread reached tenant-scoped
     * work without saying what tenant it belongs to, which is what {@link UnscopedAccessGuard}
     * acts on.
     */
    public static boolean isScopeDeclared() {
        return getCurrentOrgId() != null || isBypassed() || isUnscopedDeclared();
    }

    public static void clear() {
        currentOrgId.remove();
        bypassed.remove();
        unscopedReason.remove();
    }
}
