package org.tornotron.echno_backend.modules.documentingestion.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.modules.documentingestion.domain.Provenance;

/** Append only: save and read. No delete or update is declared, and none is inherited. */
@org.springframework.stereotype.Repository
public interface ProvenanceRepository extends Repository<Provenance, UUID> {

    Provenance save(Provenance provenance);

    @Query("SELECT p FROM Provenance p WHERE p.targetType = :targetType AND p.targetId = :targetId "
            + "ORDER BY p.appliedAt, p.targetField")
    List<Provenance> findForTarget(@Param("targetType") String targetType, @Param("targetId") String targetId,
                                   Pageable pageable);

    /** The rows for one field of one target, newest first; read with a page of one for the latest. */
    @Query("SELECT p FROM Provenance p WHERE p.targetType = :targetType AND p.targetId = :targetId "
            + "AND p.targetField = :targetField ORDER BY p.appliedAt DESC, p.id DESC")
    List<Provenance> findLatestForField(@Param("targetType") String targetType, @Param("targetId") String targetId,
                                        @Param("targetField") String targetField, Pageable pageable);

    @Query("SELECT COUNT(p) FROM Provenance p WHERE p.candidateId = :candidateId")
    long countForCandidate(@Param("candidateId") UUID candidateId);
}
