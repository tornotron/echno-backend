package org.tornotron.echno_backend.modules.documentingestion.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestDocument;

@Repository
public interface IngestDocumentRepository extends JpaRepository<IngestDocument, UUID> {

    @Query("SELECT d FROM IngestDocument d WHERE d.id = :id")
    Optional<IngestDocument> findByIdScoped(@Param("id") UUID id);

    @Query("SELECT d FROM IngestDocument d WHERE d.packageId = :packageId ORDER BY d.createdAt")
    Page<IngestDocument> findByPackage(@Param("packageId") UUID packageId, Pageable pageable);

    @Query("SELECT COUNT(d) FROM IngestDocument d WHERE d.packageId = :packageId")
    long countInPackage(@Param("packageId") UUID packageId);
}
