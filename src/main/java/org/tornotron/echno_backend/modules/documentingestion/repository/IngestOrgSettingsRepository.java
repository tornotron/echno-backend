package org.tornotron.echno_backend.modules.documentingestion.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestOrgSettings;

@Repository
public interface IngestOrgSettingsRepository extends JpaRepository<IngestOrgSettings, UUID> {

    /** By organization in the statement, so a job outside a request reads the right row too. */
    @Query("SELECT s FROM IngestOrgSettings s WHERE s.organization.id = :orgId")
    Optional<IngestOrgSettings> findForOrganization(@Param("orgId") Long orgId);
}
