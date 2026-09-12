package org.tornotron.echno_backend.modules.inspections.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.modules.inspections.ReinspectionOutcome;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One attempt to re-check a non-conformance: the NCR or defect it answers, the inspection that
 * found it, the new inspection created to re-check it, and what that re-check found.
 *
 * <p>This is the record the NCR's {@code verified_by_id} could not be: which check points were
 * re-run, by whom, and how many attempts it took. One row per attempt, numbered by
 * {@link #sequence} within its NCR or defect, and each attempt owns its re-check inspection
 * outright ({@code reinspection_inspection_id} is unique).
 *
 * <p>Links are plain ids with no JPA associations, matching the way {@link Ncr} refers to its
 * inspection; the organization is the one association, for the {@code orgFilter}. The database
 * holds foreign keys to the NCR and to both inspections, but not to the defect: an inspection
 * update replaces its defect rows, so a key there would refuse every later edit of an
 * inspection whose defect was once re-checked, which is also why {@code ncrs.defect_id} has none.
 */
@Entity
@Table(name = "inspection_reinspections",
        uniqueConstraints = @UniqueConstraint(name = "uk_reinspection_inspection",
                columnNames = "reinspection_inspection_id"),
        indexes = {
                @Index(name = "idx_reinsp_ncr", columnList = "ncr_id"),
                @Index(name = "idx_reinsp_defect", columnList = "defect_id"),
                @Index(name = "idx_reinsp_original", columnList = "original_inspection_id"),
                @Index(name = "idx_reinsp_project", columnList = "project_id")
        })
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter @Setter
@NoArgsConstructor
public class Reinspection implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "ncr_id")
    private UUID ncrId;

    @Column(name = "defect_id")
    private UUID defectId;

    @Column(name = "original_inspection_id", nullable = false)
    private UUID originalInspectionId;

    @Column(name = "reinspection_inspection_id", nullable = false)
    private UUID reinspectionInspectionId;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "requested_by_id")
    private Long requestedById;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "assigned_inspector_id")
    private Long assignedInspectorId;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private ReinspectionOutcome outcome = ReinspectionOutcome.PENDING;

    @Column(name = "outcome_by_id")
    private Long outcomeById;

    @Column(name = "outcome_at")
    private LocalDateTime outcomeAt;

    @Column(name = "remarks", length = 2000)
    private String remarks;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
