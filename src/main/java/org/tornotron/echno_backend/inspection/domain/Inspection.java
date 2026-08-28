package org.tornotron.echno_backend.inspection.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.compliance.CompliancePhase;
import org.tornotron.echno_backend.inspection.ComplianceRiskLevel;
import org.tornotron.echno_backend.inspection.InspectionCategory;
import org.tornotron.echno_backend.inspection.InspectionOrigin;
import org.tornotron.echno_backend.inspection.InspectionResult;
import org.tornotron.echno_backend.inspection.InspectionStatus;
import org.tornotron.echno_backend.inspection.InspectionTrade;
import org.tornotron.echno_backend.inspection.InspectionType;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A site quality or safety inspection. Tenant-scoped through the Hibernate
 * {@code orgFilter}; created and updated timestamps are managed by Hibernate
 * ({@code @CreationTimestamp}/{@code @UpdateTimestamp}), mirroring the portfolio
 * domains (issue, task, project) rather than the finance auditing base class.
 * Cross-domain references (project, inspector, contractor) are plain scalar ids
 * with no FK, keeping this module additive and decoupled.
 */
@Entity
@Table(name = "inspections",
        uniqueConstraints = @UniqueConstraint(name = "uk_inspection_number",
                columnNames = {"organization_id", "inspection_number"}),
        indexes = {
                @Index(name = "idx_insp_project", columnList = "project_id"),
                @Index(name = "idx_insp_status", columnList = "status"),
                @Index(name = "idx_insp_type", columnList = "type"),
                @Index(name = "idx_insp_category", columnList = "category"),
                @Index(name = "idx_insp_result", columnList = "result"),
                @Index(name = "idx_insp_scheduled_date", columnList = "scheduled_date"),
                @Index(name = "idx_insp_inspector", columnList = "inspector_id")
        })
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter @Setter
@NoArgsConstructor
public class Inspection implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "inspection_number", nullable = false, length = 30)
    private String inspectionNumber;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InspectionType type;

    // The authoritative grouping the UI and reporting filter on. Derived from the
    // type when the caller does not state one, so a row created before this column
    // existed and a new row of the same type land in the same bucket.
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private InspectionCategory category = InspectionCategory.OTHER;

    // The QA/QC stage or trade. Null on safety and compliance inspections.
    @Enumerated(EnumType.STRING)
    @Column(name = "trade", length = 50)
    private InspectionTrade trade;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InspectionStatus status = InspectionStatus.SCHEDULED;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private InspectionResult result;

    // Scalar references to core-domain records. Kept as plain ids (no cross-module FK)
    // so this module stays additive and decoupled; a later increment can wire them up.
    @Column(name = "project_id")
    private Long projectId;

    @Column(length = 300)
    private String location;

    @Column(name = "area_inspected", length = 300)
    private String areaInspected;

    @Column(name = "drawing_reference", length = 200)
    private String drawingReference;

    // Nullable: AI-generated compliance rows are created without a schedule, which
    // a project manager fills in when the compliance is planned.
    @Column(name = "scheduled_date")
    private LocalDate scheduledDate;

    @Column(name = "scheduled_time", length = 20)
    private String scheduledTime;

    @Column(name = "actual_start_time")
    private LocalDateTime actualStartTime;

    @Column(name = "actual_end_time")
    private LocalDateTime actualEndTime;

    // Duration in minutes.
    @Column(name = "duration")
    private Integer duration;

    // Nullable: AI-generated compliance rows have no inspector until one is assigned.
    @Column(name = "inspector_id")
    private Long inspectorId;

    @Column(name = "contractor_id")
    private Long contractorId;

    @Column(name = "client_representative", length = 200)
    private String clientRepresentative;

    @ElementCollection
    @CollectionTable(name = "inspection_attendees",
            joinColumns = @JoinColumn(name = "inspection_id"))
    @Column(name = "attendee", length = 200)
    private List<String> attendees = new ArrayList<>();

    @Column(name = "weather_conditions", length = 200)
    private String weatherConditions;

    @Column(length = 50)
    private String temperature;

    // Summary counts recomputed from the check items and defects on every save.
    @Column(name = "total_check_points", nullable = false)
    private int totalCheckPoints;

    @Column(name = "passed_check_points", nullable = false)
    private int passedCheckPoints;

    @Column(name = "failed_check_points", nullable = false)
    private int failedCheckPoints;

    @Column(name = "defects_found", nullable = false)
    private int defectsFound;

    // Compliance-extension fields. Present only on compliance-type inspections; null
    // on ordinary manual inspections. origin defaults to MANUAL so existing rows and
    // the manual create path are unaffected.
    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 30)
    private InspectionOrigin origin = InspectionOrigin.MANUAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "compliance_phase", length = 30)
    private CompliancePhase compliancePhase;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 30)
    private ComplianceRiskLevel riskLevel;

    @Column(name = "resolution_options", columnDefinition = "TEXT")
    private String resolutionOptions;

    /**
     * The compliance rule this inspection was generated for, null on every manual
     * inspection.
     *
     * <p>Carries a unique index across (organization_id, project_id, compliance_rule_ref)
     * over the rows where it is set, so a project cannot hold two inspections for the same
     * rule however many generation runs overlap. The index is partial and therefore lives
     * only in changelog {@code 060}: JPA cannot express the {@code WHERE} clause, and
     * without it every manual inspection's null would be indexed for nothing.
     */
    @Column(name = "compliance_rule_ref", length = 100)
    private String complianceRuleRef;

    @Column(name = "ai_rationale", columnDefinition = "TEXT")
    private String aiRationale;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @OneToMany(mappedBy = "inspection", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("lineOrder ASC")
    private List<InspectionCheckItem> checkItems = new ArrayList<>();

    @OneToMany(mappedBy = "inspection", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("lineOrder ASC")
    private List<InspectionDefect> defects = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void addCheckItem(InspectionCheckItem item) {
        item.setInspection(this);
        item.setLineOrder(this.checkItems.size());
        this.checkItems.add(item);
    }

    public void addDefect(InspectionDefect defect) {
        defect.setInspection(this);
        defect.setLineOrder(this.defects.size());
        this.defects.add(defect);
    }
}
