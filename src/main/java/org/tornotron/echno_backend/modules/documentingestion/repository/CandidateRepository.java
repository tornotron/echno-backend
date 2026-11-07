package org.tornotron.echno_backend.modules.documentingestion.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;

@Repository
public interface CandidateRepository extends JpaRepository<Candidate, UUID> {

    @Query("SELECT c FROM Candidate c WHERE c.id = :id")
    Optional<Candidate> findByIdScoped(@Param("id") UUID id);

    @Query("SELECT c FROM Candidate c WHERE c.packageId = :packageId "
            + "AND (:kind IS NULL OR c.kind = :kind) AND (:status IS NULL OR c.status = :status) "
            + "ORDER BY c.createdAt, c.id")
    Page<Candidate> findForReview(@Param("packageId") UUID packageId, @Param("kind") CandidateKind kind,
                                  @Param("status") CandidateStatus status, Pageable pageable);

    /** The next accepted rows of one kind to apply, oldest first, bounded by the page. */
    @Query("SELECT c.id FROM Candidate c WHERE c.packageId = :packageId AND c.kind = :kind "
            + "AND c.status = org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus.ACCEPTED "
            + "ORDER BY c.createdAt, c.id")
    List<UUID> findIdsToApply(@Param("packageId") UUID packageId, @Param("kind") CandidateKind kind, Pageable pageable);

    @Query("SELECT COUNT(c) FROM Candidate c WHERE c.packageId = :packageId AND c.kind = :kind AND c.status = :status")
    long countByStatus(@Param("packageId") UUID packageId, @Param("kind") CandidateKind kind,
                       @Param("status") CandidateStatus status);

    @Query("SELECT COUNT(c) FROM Candidate c WHERE c.packageId = :packageId "
            + "AND c.status = org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus.APPLIED")
    long countApplied(@Param("packageId") UUID packageId);
}
