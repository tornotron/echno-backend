package org.tornotron.echno_backend.subcontract;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single milestone within a {@link SubContract}. Owned by the header through the
 * {@code sub_contract_id} FK, which cascades persist and removal. The milestone also
 * carries its own {@code organization_id} and the {@code orgFilter} so tenant scoping
 * applies to the child rows directly, mirroring the other line-item entities in this
 * codebase (e.g. stock-adjustment line items). Status is a plain string; the frontend
 * validates it.
 */
@Entity
@Table(name = "contract_milestone")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class ContractMilestone implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sub_contract_id", nullable = false)
    private SubContract subContract;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Column(name = "completion_date")
    private LocalDate completionDate;

    @Column(name = "payment_percentage", precision = 15, scale = 2)
    private BigDecimal paymentPercentage;

    @Column(name = "amount", precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "status")
    private String status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}
