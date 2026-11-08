package org.tornotron.echno_backend.modules.documentingestion.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.documentingestion.domain.ObligationEntry;

@Repository
public interface ObligationEntryRepository extends JpaRepository<ObligationEntry, UUID> {

    @Query("SELECT o FROM ObligationEntry o WHERE o.id = :id")
    Optional<ObligationEntry> findByIdScoped(@Param("id") UUID id);

    @Query("SELECT o FROM ObligationEntry o WHERE o.candidateId = :candidateId")
    Optional<ObligationEntry> findByCandidate(@Param("candidateId") UUID candidateId);

    @Query("SELECT o FROM ObligationEntry o WHERE o.packageId = :packageId ORDER BY o.clause, o.createdAt, o.id")
    Page<ObligationEntry> findByPackage(@Param("packageId") UUID packageId, Pageable pageable);
}
