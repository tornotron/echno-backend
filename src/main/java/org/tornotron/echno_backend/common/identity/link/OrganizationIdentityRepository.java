package org.tornotron.echno_backend.common.identity.link;

import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface OrganizationIdentityRepository extends Repository<OrganizationIdentity, UUID> {

    /** Writes the link unless it is already there. */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO organization_identity (id, provider, provider_org_id, organization_id)
            VALUES (gen_random_uuid(), :provider, :providerOrgId, :organizationId)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    void link(@Param("provider") String provider, @Param("providerOrgId") String providerOrgId,
              @Param("organizationId") Long organizationId);
}
