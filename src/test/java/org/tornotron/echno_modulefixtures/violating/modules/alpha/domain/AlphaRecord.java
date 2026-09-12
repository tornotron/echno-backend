package org.tornotron.echno_modulefixtures.violating.modules.alpha.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/** A properly tenant-scoped parent, here only so the child below has something to point at. */
@Entity
public class AlphaRecord implements TenantScopedEntity {

    @Id
    private Long id;

    @ManyToOne
    private Organization organization;

    @Override
    public Organization getOrganization() {
        return organization;
    }

    @Override
    public void setOrganization(Organization organization) {
        this.organization = organization;
    }
}
