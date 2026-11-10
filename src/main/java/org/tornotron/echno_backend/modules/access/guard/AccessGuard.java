package org.tornotron.echno_backend.modules.access.guard;

import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.CallerAccess;

/**
 * The {@code @access} bean {@code @PreAuthorize} expressions ask, in place of the role-name
 * checks:
 * <pre>
 *   &#64;PreAuthorize("&#64;access.can('wbs', 'edit')")
 *   &#64;PreAuthorize("&#64;access.isMember()")
 *   &#64;PreAuthorize("&#64;access.canIn(#id, 'org-admin', 'delete')")
 * </pre>
 * Each answers for the current request's subject and refuses when there is none. {@code canIn}
 * answers in the organization its first argument names, for a handler that addresses an
 * organization by id, which need not be the one the request is for. A malformed area
 * or action also answers false, so a typo in a guard refuses the caller and never becomes a 500;
 * {@code AccessGuardLiteralTest} fails the build on one before it gets that far.
 *
 * <p>Checks on one record, such as whether a client may open this bill, are made in the service
 * with {@link AccessService#check(org.tornotron.echno_backend.modules.access.api.AccessSubject,
 * AccessPermission, org.tornotron.echno_backend.modules.access.api.RecordFacts)}, where the record
 * is at hand; a guard only decides whether the caller may use the area at all.
 */
@Component("access")
@Slf4j
public class AccessGuard implements CallerAccess {

    private final CurrentAccessSubject current;
    private final AccessService access;

    public AccessGuard(CurrentAccessSubject current, AccessService access) {
        this.current = current;
        this.access = access;
    }

    /** Whether the caller is an active member of the request's organization. */
    public boolean isMember() {
        return current.resolve().map(access::isActiveMember).orElse(false);
    }

    /** Whether the caller may do {@code action} somewhere in {@code area}. */
    @Override
    public boolean can(String area, String action) {
        AccessPermission permission;
        try {
            permission = new AccessPermission(area, action);
        } catch (IllegalArgumentException e) {
            log.error("Refusing a guard with a malformed permission: {}", e.getMessage());
            return false;
        }
        return current.resolve().map(subject -> access.check(subject, permission)).orElse(false);
    }

    /**
     * Whether the caller may do {@code action} somewhere in {@code area} of {@code organizationId}.
     *
     * <p>The same question as {@link #can}, asked in the named organization rather than the
     * request's: the caller is resolved as their membership there, and their grants there decide,
     * with the same rules (an inactive or absent membership refuses, and a project-limited grant
     * counts only in a scope-aware area). The request's organization is pinned to the named one for
     * the check, because the facts are read in transactions the organization filter scopes to it,
     * and restored afterwards. A null organization or a malformed key refuses.
     */
    public boolean canIn(Long organizationId, String area, String action) {
        AccessPermission permission;
        try {
            permission = new AccessPermission(area, action);
        } catch (IllegalArgumentException e) {
            log.error("Refusing a guard with a malformed permission: {}", e.getMessage());
            return false;
        }
        if (organizationId == null) {
            return false;
        }
        return inOrganization(organizationId,
                () -> current.resolve().map(subject -> access.check(subject, permission)).orElse(false));
    }

    /** Runs {@code work} with the request's organization pinned to {@code organizationId}, then restores it. */
    private static <T> T inOrganization(Long organizationId, Supplier<T> work) {
        Long previousOrganization = TenantContext.getCurrentOrgId();
        boolean previousBypass = TenantContext.isBypassed();
        String previousUnscopedReason = TenantContext.getUnscopedReason();
        TenantContext.setCurrentOrgId(organizationId);
        TenantContext.setBypass(false);
        TenantContext.clearUnscoped();
        try {
            return work.get();
        } finally {
            TenantContext.clear();
            if (previousOrganization != null) {
                TenantContext.setCurrentOrgId(previousOrganization);
            }
            if (previousBypass) {
                TenantContext.setBypass(true);
            }
            if (previousUnscopedReason != null) {
                TenantContext.declareUnscoped(previousUnscopedReason);
            }
        }
    }
}
