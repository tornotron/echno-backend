package org.tornotron.echno_backend.modules.documentingestion.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.documentingestion.domain.CompanyEvidence;

@Repository
public interface CompanyEvidenceRepository extends JpaRepository<CompanyEvidence, UUID> {

    @Query("SELECT e FROM CompanyEvidence e WHERE e.id = :id")
    Optional<CompanyEvidence> findByIdScoped(@Param("id") UUID id);

    /** Files whose upload was registered, by section then upload time. */
    @Query("SELECT e FROM CompanyEvidence e WHERE e.attachmentId IS NOT NULL ORDER BY e.section, e.createdAt, e.id")
    List<CompanyEvidence> findUploaded(Pageable page);

    @Query("SELECT COUNT(e) FROM CompanyEvidence e")
    long countAll();
}
