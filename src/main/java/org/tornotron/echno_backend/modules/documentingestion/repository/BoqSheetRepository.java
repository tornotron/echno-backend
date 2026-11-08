package org.tornotron.echno_backend.modules.documentingestion.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.documentingestion.domain.BoqSheet;

@Repository
public interface BoqSheetRepository extends JpaRepository<BoqSheet, UUID> {

    @Query("SELECT s FROM BoqSheet s WHERE s.id = :id")
    Optional<BoqSheet> findByIdScoped(@Param("id") UUID id);

    @Query("SELECT s FROM BoqSheet s WHERE s.documentId = :documentId ORDER BY s.sheetIndex")
    List<BoqSheet> findForDocument(@Param("documentId") UUID documentId);
}
