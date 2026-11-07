package org.tornotron.echno_backend.modules.documentingestion.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.documentingestion.domain.SourceRef;

@Repository
public interface SourceRefRepository extends JpaRepository<SourceRef, UUID> {

    /** Bounded by the caller: one page of candidates at most. */
    @Query("SELECT s FROM SourceRef s WHERE s.candidateId IN :candidateIds ORDER BY s.candidateId, s.field")
    List<SourceRef> findForCandidates(@Param("candidateIds") Collection<UUID> candidateIds);

    @Query("SELECT COUNT(s) FROM SourceRef s WHERE s.candidateId = :candidateId")
    long countForCandidate(@Param("candidateId") UUID candidateId);
}
