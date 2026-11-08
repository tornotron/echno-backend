package org.tornotron.echno_backend.modules.documentingestion.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.documentingestion.domain.LlmCall;

/**
 * The ledger. Every query names the organization in the statement as well as through the filter, since
 * the budget is read on a replica's job thread.
 */
@Repository
public interface LlmCallRepository extends JpaRepository<LlmCall, UUID> {

    /** Spend in tokens and estimated dollars. */
    interface Spend {
        Long getTokens();

        BigDecimal getCostUsd();

        Long getCalls();
    }

    @Query("SELECT COALESCE(SUM(COALESCE(c.promptTokens, 0) + COALESCE(c.completionTokens, 0)), 0) AS tokens, "
            + "COALESCE(SUM(c.estimatedCostUsd), 0) AS costUsd, COUNT(c) AS calls FROM LlmCall c "
            + "WHERE c.organization.id = :orgId AND c.packageId = :packageId")
    Spend spendForPackage(@Param("orgId") Long orgId, @Param("packageId") UUID packageId);

    @Query("SELECT COALESCE(SUM(COALESCE(c.promptTokens, 0) + COALESCE(c.completionTokens, 0)), 0) AS tokens, "
            + "COALESCE(SUM(c.estimatedCostUsd), 0) AS costUsd, COUNT(c) AS calls FROM LlmCall c "
            + "WHERE c.organization.id = :orgId AND c.calledAt >= :since")
    Spend spendSince(@Param("orgId") Long orgId, @Param("since") LocalDateTime since);

    /** Chunk keys of a job that were read: a retried job skips them. */
    @Query("SELECT DISTINCT c.chunkKey FROM LlmCall c WHERE c.organization.id = :orgId AND c.jobId = :jobId "
            + "AND c.outcome = :outcome")
    List<String> findChunkKeysWithOutcome(@Param("orgId") Long orgId, @Param("jobId") UUID jobId,
                                          @Param("outcome") org.tornotron.echno_backend.modules.documentingestion.domain.LlmCallOutcome outcome);

    @Query("SELECT COALESCE(MAX(c.callNumber), 0) FROM LlmCall c WHERE c.organization.id = :orgId "
            + "AND c.jobId = :jobId AND c.chunkKey = :chunkKey")
    int lastCallNumber(@Param("orgId") Long orgId, @Param("jobId") UUID jobId, @Param("chunkKey") String chunkKey);

    @Query("SELECT c FROM LlmCall c WHERE (:packageId IS NULL OR c.packageId = :packageId) "
            + "AND (:documentId IS NULL OR c.documentId = :documentId) ORDER BY c.calledAt DESC, c.id")
    Page<LlmCall> findForAudit(@Param("packageId") UUID packageId, @Param("documentId") UUID documentId,
                               Pageable pageable);
}
