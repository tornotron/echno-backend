package org.tornotron.echno_backend.finance.budget.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.domain.BaseEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.util.UUID;

/**
 * A budget head (cost category) in a project budget: materials, labour, plant, overheads and so on.
 *
 * <p>Cost categories are an org-level master list. A project budget is expressed as a set of
 * {@link BudgetAllocation} rows, one per category, and a construction invoice line is tagged to a
 * category so its cost rolls up under that head in the project cost-control view. An optional
 * {@code expenseAccount} aligns a head with a ledger expense account so budget reporting and the
 * chart of accounts line up. A category is deactivated rather than deleted so tagged history survives.
 */
@Entity
@Table(name = "cost_category",
        uniqueConstraints = @UniqueConstraint(name = "uk_cost_category_name",
                columnNames = {"organization_id", "name"}))
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class CostCategory extends BaseEntity implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 20)
    private String code;

    /** Optional ledger expense account this head maps to, for reporting alignment. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "expense_account_id")
    private Account expenseAccount;

    @Column(nullable = false)
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}
