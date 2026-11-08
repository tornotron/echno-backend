package org.tornotron.echno_backend.modules.documentingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * One call that left Echno for the outside AI service: which pages of which document, the model and
 * prompt version, tokens, the estimated cost, and how it ended. Holds no document text, so an admin can
 * see exactly what was sent without the ledger becoming a second copy of it (design section 7.4).
 *
 * <p>{@code chunkKey} names the chunk by its first and last page. A chunk can be sent more than once
 * (backoff on a 429, one retry on an answer that is not JSON), so each call of a chunk has its own
 * {@code callNumber}, and the three together are unique.
 */
@Entity
@Table(name = "doc_ingest_llm_call")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class LlmCall implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "extractor_key", nullable = false, length = 60)
    private String extractorKey;

    @Column(name = "chunk_key", nullable = false, length = 40)
    private String chunkKey;

    @Column(name = "call_number", nullable = false)
    private int callNumber;

    @Column(name = "page_from", nullable = false)
    private int pageFrom;

    @Column(name = "page_to", nullable = false)
    private int pageTo;

    /** The pages sent, as {@code 1-4,6,9}: a chunk skips pages that were not sent. */
    @Column(nullable = false, length = 2000)
    private String pages;

    @Column(length = 120)
    private String model;

    @Column(name = "model_version", length = 120)
    private String modelVersion;

    @Column(name = "prompt_version", nullable = false, length = 40)
    private String promptVersion;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    /** Null when the configured rates are zero: the tokens are known and the price is not. */
    @Column(name = "estimated_cost_usd", precision = 14, scale = 6)
    private BigDecimal estimatedCostUsd;

    @Column(name = "finish_reason", length = 40)
    private String finishReason;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LlmCallOutcome outcome;

    @Column(name = "called_at", nullable = false)
    private LocalDateTime calledAt;
}
