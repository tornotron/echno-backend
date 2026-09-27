package org.tornotron.echno_backend.modules.workprogress.domain;

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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * One progress inspection of one schedule activity: on this date the activity was found done,
 * partly done or not done, with the dates, the delay and its reason.
 *
 * <p>The aggregate of the module, tenant scoped behind {@code orgFilter}. Append-only: a record
 * is never edited, the next inspection records the new state. The planned finish and the delay
 * are snapshots taken when it was recorded, so a later change to the schedule does not rewrite
 * what the inspector saw. References into the core (the project, the activity, the spatial node,
 * the inspector) are plain ids with foreign keys in the changelog.
 */
@Entity
@Table(name = "work_progress_inspection",
        indexes = {
                @Index(name = "idx_wpi_organization", columnList = "organization_id"),
                @Index(name = "idx_wpi_project_date", columnList = "organization_id, project_id, inspection_date"),
                @Index(name = "idx_wpi_wbs_element", columnList = "wbs_element_id, inspection_date")
        })
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class ProgressInspection implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "wbs_element_id", nullable = false)
    private Long wbsElementId;

    @Column(name = "inspection_date", nullable = false)
    private LocalDate inspectionDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProgressOutcome outcome;

    @Column(name = "percent_complete", nullable = false, precision = 5, scale = 2)
    private BigDecimal percentComplete;

    @Column(name = "actual_start_date")
    private LocalDate actualStartDate;

    @Column(name = "actual_finish_date")
    private LocalDate actualFinishDate;

    @Column(name = "forecast_finish_date")
    private LocalDate forecastFinishDate;

    @Column(name = "planned_finish_date")
    private LocalDate plannedFinishDate;

    @Column(name = "delay_days")
    private Integer delayDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "delay_reason", length = 30)
    private DelayReason delayReason;

    @Column(name = "delay_notes", columnDefinition = "TEXT")
    private String delayNotes;

    // The building, floor or zone where the work was seen, when the project has a site structure.
    @Column(name = "spatial_node_id")
    private UUID spatialNodeId;

    @Column(columnDefinition = "TEXT")
    private String remarks;

    @Column(name = "inspector_employee_id")
    private Long inspectorEmployeeId;

    @Column(name = "recorded_by")
    private Long recordedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
