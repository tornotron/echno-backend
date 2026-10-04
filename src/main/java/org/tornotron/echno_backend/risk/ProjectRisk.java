package org.tornotron.echno_backend.risk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * One risk on a project's risk register: what could go wrong, how likely and how bad, and what is
 * being done about it.
 *
 * <p>Tenant scoped behind {@code orgFilter}. The register used to live in the browser of whoever
 * typed it in; it is kept here so everyone on the project reads the same one. The vocabularies
 * (probability, impact, status, response, category) are the codes the web client uses, listed in
 * {@link RiskScale}. Scores are derived from probability and impact on every write and stored so
 * the register can be sorted and filtered on them.
 *
 * <p>{@link #riskNumber} is the project's running number, shown as {@code R-001}. It is unique per
 * project and issued by the service as one past the highest the project holds.
 */
@Entity
@Table(name = "project_risk",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_project_risk_number", columnNames = {"project_id", "risk_number"}),
                @UniqueConstraint(name = "uq_project_risk_import_ref", columnNames = {"project_id", "import_ref"})
        },
        indexes = @Index(name = "idx_project_risk_org_project", columnList = "organization_id, project_id"))
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class ProjectRisk implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "risk_number", nullable = false)
    private Integer riskNumber;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 4000)
    private String description;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(name = "sub_category", length = 255)
    private String subCategory;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(length = 255)
    private String owner;

    @Column(nullable = false, length = 16)
    private String probability;

    @Column(nullable = false, length = 16)
    private String impact;

    @Column(name = "risk_score", nullable = false)
    private Integer riskScore;

    @Column(name = "residual_probability", nullable = false, length = 16)
    private String residualProbability;

    @Column(name = "residual_impact", nullable = false, length = 16)
    private String residualImpact;

    @Column(name = "residual_score", nullable = false)
    private Integer residualScore;

    @Column(name = "response_type", nullable = false, length = 16)
    private String responseType;

    @Column(name = "contingency_plan", length = 4000)
    private String contingencyPlan;

    @Column(name = "identified_date")
    private LocalDate identifiedDate;

    @Column(name = "review_date")
    private LocalDate reviewDate;

    @Column(name = "closed_date")
    private LocalDate closedDate;

    @Column(name = "cost_impact", precision = 19, scale = 2)
    private BigDecimal costImpact;

    @Column(name = "schedule_impact_days")
    private Integer scheduleImpactDays;

    /**
     * Where an imported risk came from: its id in the browser storage it was imported out of.
     * Unique per project, so importing the same browser's register twice adds nothing the second
     * time. Null for a risk recorded here.
     */
    @Column(name = "import_ref", length = 64)
    private String importRef;

    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", length = 100, updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
