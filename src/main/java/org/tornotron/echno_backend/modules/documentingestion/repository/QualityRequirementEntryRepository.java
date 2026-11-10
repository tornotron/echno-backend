package org.tornotron.echno_backend.modules.documentingestion.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.documentingestion.domain.QualityRequirementEntry;

@Repository
public interface QualityRequirementEntryRepository extends JpaRepository<QualityRequirementEntry, UUID> {

    @Query("SELECT q FROM QualityRequirementEntry q WHERE q.id = :id")
    Optional<QualityRequirementEntry> findByIdScoped(@Param("id") UUID id);

    @Query("SELECT q FROM QualityRequirementEntry q WHERE q.id IN :ids ORDER BY q.createdAt, q.id")
    List<QualityRequirementEntry> findByIdsScoped(@Param("ids") Collection<UUID> ids);

    @Query("SELECT q FROM QualityRequirementEntry q WHERE q.candidateId = :candidateId")
    Optional<QualityRequirementEntry> findByCandidate(@Param("candidateId") UUID candidateId);

    @Query("SELECT q FROM QualityRequirementEntry q WHERE q.packageId = :packageId "
            + "ORDER BY q.tradeName, q.activity, q.subject, q.createdAt, q.id")
    Page<QualityRequirementEntry> findByPackage(@Param("packageId") UUID packageId, Pageable pageable);

    @Query("SELECT q FROM QualityRequirementEntry q WHERE q.projectId = :projectId "
            + "ORDER BY q.tradeName, q.activity, q.subject, q.createdAt, q.id")
    Page<QualityRequirementEntry> findByProject(@Param("projectId") Long projectId, Pageable pageable);
}
