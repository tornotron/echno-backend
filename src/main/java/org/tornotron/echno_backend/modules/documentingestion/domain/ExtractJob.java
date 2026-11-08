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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * One extractor's run over one document or segment, on the backend's own queue. Every replica polls;
 * one conditional update claims; the lease moves with each chunk, so a replica that dies is recovered
 * by the next poll on any replica. {@code result} is the extractor's working state between chunks,
 * such as the classes found per page, so a retried job keeps what earlier attempts read.
 */
@Entity
@Table(name = "doc_ingest_extract_job")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class ExtractJob implements TenantScopedEntity {

    public static final int DEFAULT_MAX_ATTEMPTS = 3;

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

    @Column(name = "segment_id")
    private UUID segmentId;

    @Column(name = "extractor_key", nullable = false, length = 60)
    private String extractorKey;

    @Column(name = "extractor_version", nullable = false, length = 20)
    private String extractorVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExtractJobStatus status = ExtractJobStatus.QUEUED;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = DEFAULT_MAX_ATTEMPTS;

    @Column(name = "claimed_by", length = 120)
    private String claimedBy;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "chunks_total", nullable = false)
    private int chunksTotal;

    @Column(name = "chunks_done", nullable = false)
    private int chunksDone;

    @Column(name = "chunks_failed", nullable = false)
    private int chunksFailed;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "failed_pages", columnDefinition = "jsonb")
    private List<UnreadPage> failedPages = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "unread_pages", columnDefinition = "jsonb")
    private List<UnreadPage> unreadPages = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    private Map<String, Object> result = new LinkedHashMap<>();

    /** Why the job waits, paused or failed, in one plain sentence; null while it runs normally. */
    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "queued_at", nullable = false)
    private LocalDateTime queuedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
