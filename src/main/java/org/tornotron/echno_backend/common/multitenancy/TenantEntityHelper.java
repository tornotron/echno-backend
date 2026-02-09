package org.tornotron.echno_backend.common.multitenancy;

import lombok.RequiredArgsConstructor;
import org.tornotron.echno_backend.common.exception.TenantIdMissingException;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TenantEntityHelper {

    private final OrganizationRepository organizationRepository;

    public Organization resolveCurrentOrganization() {
        Long orgId = TenantContext.getCurrentOrgId();
        if (orgId == null) {
            throw new TenantIdMissingException("No organization context set — X-Organization-Id header required");
        }
        return organizationRepository.getReferenceById(orgId);
    }
}
