package org.tornotron.echno_backend.finance.budget.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.finance.ledger.domain.BaseEntity;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The amount allocated to one budget head (cost category) on one project.
 *
 * <p>A project's budget is the set of these rows: one allocation per cost category, each carrying the
 * money set aside for that head. The pair (project, cost category) is unique, so upserting an
 * allocation replaces the amount rather than adding a second row. Cost-control compares this allocated
 * amount against the committed and spent amounts rolled up from tagged construction invoice lines.
 */
@Entity
@Table(name = "budget_allocation",
        uniqueConstraints = @UniqueConstraint(name = "uk_budget_allocation_project_category",
                columnNames = {"project_id", "cost_category_id"}),
        indexes = {
                @Index(name = "idx_budget_allocation_project", columnList = "project_id"),
                @Index(name = "idx_budget_allocation_category", columnList = "cost_category_id")
        })
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class BudgetAllocation extends BaseEntity implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cost_category_id", nullable = false)
    private CostCategory costCategory;

    @Column(name = "allocated_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal allocatedAmount = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}
