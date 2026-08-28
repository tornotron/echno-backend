package org.tornotron.echno_backend.inspection.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.inspection.DefectSeverity;
import org.tornotron.echno_backend.inspection.NcrStatus;
import org.tornotron.echno_backend.inspection.NcrType;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A non-conformance report: the accountability wrapper over work that failed an
 * inspection. Tenant-scoped through the Hibernate {@code orgFilter}, with its own
 * {@code NCR} document series.
 *
 * <p>The corrective action itself stays on {@link InspectionDefect}, which already
 * records what has to be done, by whom and by when. What a defect cannot carry is
 * the chain of custody the functional spec asks for: who raised the
 * non-conformance, which site engineer owns it, who re-inspected it and who
 * finally closed it, with a status that cannot jump. That chain is this entity,
 * and it is why an NCR is a separate record rather than four more columns on the
 * defect: one defect may be raised, rejected and reopened more than once, and each
 * pass needs its own owner and its own dates.
 *
 * <p>The links to the inspection and the defect are plain scalar ids with no FK,
 * matching the way {@link Inspection} refers to its project. This keeps the module
 * additive and lets an NCR outlive the defect row it started from.
 */
@Entity
@Table(name = "ncrs",
        uniqueConstraints = @UniqueConstraint(name = "uk_ncr_number",
                columnNames = {"organization_id", "ncr_number"}),
        indexes = {
                @Index(name = "idx_ncr_inspection", columnList = "inspection_id"),
                @Index(name = "idx_ncr_defect", columnList = "defect_id"),
                @Index(name = "idx_ncr_status", columnList = "status"),
                @Index(name = "idx_ncr_type", columnList = "type"),
                @Index(name = "idx_ncr_site_engineer", columnList = "site_engineer_id")
        })
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter @Setter
@NoArgsConstructor
public class Ncr implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ncr_number", nullable = false, length = 30)
    private String ncrNumber;

    // Fixed when the NCR is raised: it decides who may close it.
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private NcrType type;

    @Column(name = "inspection_id", nullable = false)
    private UUID inspectionId;

    // The specific defect this NCR is about, when it is about one. An NCR may also
    // be raised against the inspection as a whole.
    @Column(name = "defect_id")
    private UUID defectId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 20)
    private DefectSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private NcrStatus status = NcrStatus.OPEN;

    // Scalar employee references, as elsewhere in this module.
    @Column(name = "site_engineer_id")
    private Long siteEngineerId;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Column(name = "raised_by_id")
    private Long raisedById;

    @Column(name = "closed_by_id")
    private Long closedById;

    /**
     * Who accepted or refused the corrective work on re-inspection. Recorded
     * because a trail that says the work was verified without saying by whom is not
     * a trail: the whole reason sign-off is role-gated is that it matters which
     * qualified person accepted it.
     */
    @Column(name = "verified_by_id")
    private Long verifiedById;

    // What the site engineer reports on marking the corrective work complete, and
    // what a verifier writes when sending it back or reopening it. Both are part of
    // the record a client reads on a closed NCR, so neither is a transient note.
    @Column(name = "corrective_action_remarks", length = 2000)
    private String correctiveActionRemarks;

    @Column(name = "verification_remarks", length = 2000)
    private String verificationRemarks;

    @Column(name = "corrective_action_completed_at")
    private LocalDateTime correctiveActionCompletedAt;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
