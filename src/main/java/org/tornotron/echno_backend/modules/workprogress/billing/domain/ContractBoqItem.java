package org.tornotron.echno_backend.modules.workprogress.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

/**
 * One line of a sub-contract's bill of quantities: what the contractor is paid for, in what
 * unit, how much of it the contract covers and at what rate. Running account bills measure
 * against these lines. The amount is the contract quantity times the rate, rounded to paise,
 * and is kept so the contract's BOQ total reads without arithmetic.
 */
@Entity
@Table(name = "contract_boq_item")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class ContractBoqItem implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "sub_contract_id", nullable = false)
    private Long subContractId;

    @Column(name = "item_code", nullable = false, length = 50)
    private String itemCode;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 30)
    private String unit;

    @Column(name = "contract_quantity", nullable = false, precision = 18, scale = 3)
    private BigDecimal contractQuantity;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal rate;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    // The schedule activity whose inspected progress suggests a quantity to claim. Optional.
    @Column(name = "wbs_element_id")
    private Long wbsElementId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
