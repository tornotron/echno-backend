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
 */
public class TenantIsolationLoadListener implements PostLoadEventListener {

    @Override
    public void onPostLoad(PostLoadEvent event) {
        if (!(event.getEntity() instanceof TenantScopedEntity scoped)) {
            return;
        }

        Long contextOrgId = TenantContext.getCurrentOrgId();
        if (contextOrgId == null || TenantContext.isBypassed()) {
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
