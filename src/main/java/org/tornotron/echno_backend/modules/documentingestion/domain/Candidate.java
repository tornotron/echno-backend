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
 * A value an extractor proposes. {@code payload} is what was extracted and never changes;
 * {@code acceptedPayload} is what a person accepted, with any field they corrected, so both values
 * survive. Nothing here reaches a live table until the apply step, in the request of a signed-in
 * person, and that step writes provenance in the same transaction.
 */
@Entity
@Table(name = "doc_ingest_candidate")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class Candidate implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "segment_id")
    private UUID segmentId;

    /** The extract job and chunk that proposed it; null for deterministic readers such as Excel BOQ. */
    @Column(name = "extract_job_id")
    private UUID extractJobId;

    @Column(name = "chunk_key", length = 40)
    private String chunkKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private CandidateKind kind;

    @Column(name = "natural_key", length = 200)
    private String naturalKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "accepted_payload", columnDefinition = "jsonb")
    private Map<String, Object> acceptedPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "flags", columnDefinition = "jsonb", nullable = false)
    private List<CandidateFlag> flags = new ArrayList<>();

    // A text array, as checklist_templates.applicable_element_types: a JSON-typed List<String> is
    // cached by Hibernate for every other List<String> column and breaks schema validation there.
    @Column(name = "acknowledged_flags", columnDefinition = "text[]")
    private List<String> acknowledgedFlags;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExtractionMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CandidateStatus status = CandidateStatus.PROPOSED;

    @Column(name = "supersedes_candidate_id")
    private UUID supersedesCandidateId;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "applied_target_type", length = 60)
    private String appliedTargetType;

    @Column(name = "applied_target_id", length = 100)
    private String appliedTargetId;

    @Column(name = "applied_by")
    private Long appliedBy;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    @Column(name = "apply_error", columnDefinition = "TEXT")
    private String applyError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
