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
 * Rows with no organization (legacy data pending backfill) are left alone: isolation
 * cannot be enforced on them here.
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
        if (organization == null) {
            return;
        }

        // Reading the id off a lazy proxy does not initialize it.
        Long entityOrgId = organization.getId();
        if (entityOrgId != null && !entityOrgId.equals(contextOrgId)) {
            throw new TenantAccessDeniedException(
                    "Cross-tenant access denied: " + event.getEntity().getClass().getSimpleName()
                            + " belongs to organization " + entityOrgId
                            + " but the request is scoped to organization " + contextOrgId);
        }
    }
}
