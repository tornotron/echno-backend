package org.tornotron.echno_backend.modules.inspections.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.modules.inspections.DefectSeverity;
import org.tornotron.echno_backend.modules.inspections.ObservationOutcomeKind;
import org.tornotron.echno_backend.modules.inspections.ObservationReviewStatus;
import org.tornotron.echno_backend.modules.inspections.ObservationSource;
import org.tornotron.echno_backend.organization.Organization;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Something seen on site: by a person, a model, a drone, a ground robot or a fixed camera.
 * It gets a persistent id the moment it is recorded, ahead of any human verification, and
 * keeps that id through the decision that turns it into a check-item result, a defect, an
 * NCR or an inspection.
 *
 * <p>The source is metadata. Which of the device and model columns are filled depends on it;
 * the review workflow, the evidence handling and the outcome linkage do not. A proposal is
 * never rewritten by its reviewer: the columns stay as the producer wrote them and a
 * reviewer's edits live in {@link #reviewChanges}, which is what makes a rejected or modified
 * row usable as a training example later.
 *
 * <p>{@code inspectionId} is nullable. A drone mission or the compliance model can observe
 * before any inspection exists for it. {@code outcomeRef} is a plain id with no FK because it
 * points at one of four tables depending on {@link #outcomeKind}; the reverse links
 * ({@code inspection_defects.observation_id}, {@code ncrs.observation_id}) are the FKs.
 * Design: {@code docs/specs/2026-09-12-qaqc-observation.md}.
 */
@Entity
@Table(name = "inspection_observations",
        indexes = {
                @Index(name = "idx_insp_obs_project_status", columnList = "project_id, review_status"),
                @Index(name = "idx_insp_obs_inspection", columnList = "inspection_id"),
                @Index(name = "idx_insp_obs_spatial_node", columnList = "spatial_node_id"),
                @Index(name = "idx_insp_obs_source_observed", columnList = "source, observed_at"),
                @Index(name = "idx_insp_obs_outcome", columnList = "outcome_kind, outcome_ref"),
                @Index(name = "idx_insp_obs_organization", columnList = "organization_id")
        })
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class Observation implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "inspection_id")
    private UUID inspectionId;

    @Column(name = "spatial_node_id")
    private UUID spatialNodeId;

    @Column(name = "location_note", length = 300)
    private String locationNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private ObservationSource source;

    @Column(name = "source_device_id", length = 100)
    private String sourceDeviceId;

    @Column(name = "mission_ref", length = 100)
    private String missionRef;

    @Column(name = "capture_ref", length = 200)
    private String captureRef;

    // The producer's own id for the finding; unique per organisation when set, so a retried
    // upload finds its earlier row instead of making a second one.
    @Column(name = "external_ref", length = 200)
    private String externalRef;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "model_version", length = 50)
    private String modelVersion;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "observed_at", nullable = false)
    private LocalDateTime observedAt;

    // Employee who saw it, for a HUMAN source.
    @Column(name = "reported_by_id")
    private Long reportedById;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(length = 200)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "suggested_severity", length = 20)
    private DefectSeverity suggestedSeverity;

    // Pointers to captures held outside the Echno attachment store, or to attachments in it
    // by id. Shape is the producer's; Echno stores and returns it unchanged.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_refs", columnDefinition = "jsonb")
    private List<Map<String, Object>> evidenceRefs;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 20)
    private ObservationReviewStatus reviewStatus = ObservationReviewStatus.PENDING;

    @Column(name = "reviewed_by_id")
    private Long reviewedById;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;

    // One entry per field the reviewer changed: {field, before, after}.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "review_changes", columnDefinition = "jsonb")
    private List<Map<String, Object>> reviewChanges;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome_kind", nullable = false, length = 20)
    private ObservationOutcomeKind outcomeKind = ObservationOutcomeKind.NONE;

    @Column(name = "outcome_ref")
    private UUID outcomeRef;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    public boolean isReviewed() {
        return reviewStatus != ObservationReviewStatus.PENDING;
    }
}
