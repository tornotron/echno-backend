package org.tornotron.echno_backend.common.multitenancy;

import org.hibernate.event.spi.PostLoadEvent;
import org.hibernate.event.spi.PostLoadEventListener;
import org.tornotron.echno_backend.common.exception.TenantAccessDeniedException;
import org.tornotron.echno_backend.organization.Organization;

/**
 * Enforces tenant isolation at the ORM load boundary. The Hibernate {@code orgFilter}
 * scopes queries, but it never applies to primary-key loads ({@code findById}) and
 * only runs where the filter was enabled, so a caller can still read another
 * organization's row by id. This listener runs on every entity load and, when a
 * tenant context is active and not bypassed, rejects any {@link TenantScopedEntity}
 * that belongs to a different organization.
 *
 * <p>Legitimate cross-tenant work (seeding, startup, system-admin operations) runs
 * under {@code @BypassTenantFilter}, which sets the bypass flag this listener honors.
 * A tenant-scoped row with no organization cannot be proven to belong to the caller,
 * so under an active tenant context it is denied rather than leaked. Every create
 * path sets the organization, so a null here is legacy data pending backfill, and
 * denying is the safe failure.
 *
 * <p>Work that belongs to no organization at all declares that with {@link WithoutTenant}
 * or {@code TenantContext.declareUnscoped}, and is let through the same way a bypass is.
 * What is no longer let through is work that declares nothing: until #507 this listener
 * returned early on a null organization id, which is the same condition
 * {@link HibernateFilterConfig} skips the {@code orgFilter} on, so the two mechanisms that
 * read as defence in depth in fact failed together on identical input. That case now goes
 * to {@link UnscopedAccessGuard}, which denies it by default.
 */
public class TenantIsolationLoadListener implements PostLoadEventListener {

    private final UnscopedAccessGuard unscopedAccessGuard;

    public TenantIsolationLoadListener(UnscopedAccessGuard unscopedAccessGuard) {
        this.unscopedAccessGuard = unscopedAccessGuard;
    }

    @Override
    public void onPostLoad(PostLoadEvent event) {
        if (!(event.getEntity() instanceof TenantScopedEntity scoped)) {
            return;
        }

        if (TenantContext.isBypassed()) {
            return;
        }

        Long contextOrgId = TenantContext.getCurrentOrgId();
        if (contextOrgId == null) {
            if (!TenantContext.isUnscopedDeclared()) {
                unscopedAccessGuard.onUnscopedLoad(event.getEntity().getClass());
            }
            // Under an unscoped declaration, or a policy that only observes, there is no
            // organization to compare the row against, so nothing more can be checked here.
            return;
        }

        Organization organization = scoped.getOrganization();
        // Reading the id off a lazy proxy does not initialize it.
        Long entityOrgId = organization == null ? null : organization.getId();

        // Deny a foreign organization, and also a null one: a tenant-scoped row
        // with no organization cannot be proven to belong to the caller's tenant,
        // and letting it through was the earlier hole (any tenant could read a
        // null-org row by id).
        if (entityOrgId == null || !entityOrgId.equals(contextOrgId)) {
            throw new TenantAccessDeniedException(
                    "Cross-tenant access denied: " + event.getEntity().getClass().getSimpleName()
                            + (entityOrgId == null
                                    ? " has no organization"
                                    : " belongs to organization " + entityOrgId)
                            + " but the request is scoped to organization " + contextOrgId);
        }
    }
}
