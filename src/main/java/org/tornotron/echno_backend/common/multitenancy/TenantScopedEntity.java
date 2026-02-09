package org.tornotron.echno_backend.common.multitenancy;

import org.tornotron.echno_backend.organization.Organization;

public interface TenantScopedEntity {

    Organization getOrganization();

    void setOrganization(Organization organization);
}
