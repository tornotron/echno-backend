package org.tornotron.echno_backend.modules.documentingestion.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.documentingestion.domain.QualificationRequirementEntry;

@Repository
public interface QualificationRequirementEntryRepository extends JpaRepository<QualificationRequirementEntry, UUID> {

    @Query("SELECT q FROM QualificationRequirementEntry q WHERE q.id = :id")
    Optional<QualificationRequirementEntry> findByIdScoped(@Param("id") UUID id);

    @Query("SELECT q FROM QualificationRequirementEntry q WHERE q.candidateId = :candidateId")
    Optional<QualificationRequirementEntry> findByCandidate(@Param("candidateId") UUID candidateId);

    @Query("SELECT q FROM QualificationRequirementEntry q WHERE q.packageId = :packageId ORDER BY q.createdAt, q.id")
    List<QualificationRequirementEntry> findByPackage(@Param("packageId") UUID packageId, Pageable page);
}
