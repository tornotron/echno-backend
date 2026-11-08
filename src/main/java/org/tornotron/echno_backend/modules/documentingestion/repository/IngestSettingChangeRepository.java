package org.tornotron.echno_backend.modules.documentingestion.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestSettingChange;

@Repository
public interface IngestSettingChangeRepository extends JpaRepository<IngestSettingChange, UUID> {

    @Query("SELECT c FROM IngestSettingChange c WHERE c.organization.id = :orgId ORDER BY c.changedAt DESC, c.id")
    List<IngestSettingChange> findRecent(@Param("orgId") Long orgId, Pageable pageable);
}
