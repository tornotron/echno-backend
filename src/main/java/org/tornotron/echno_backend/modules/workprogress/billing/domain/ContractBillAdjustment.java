package org.tornotron.echno_backend.modules.workprogress.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One commercial adjustment on a bill: added (GST, an approved variation, an extra item) or
 * deducted (retention, advance recovery, TDS, a penalty). Manual lines are entered before
 * certification; rule lines are written at certification from the contract's rules, with the
 * amount as computed then. Owned by its bill, which carries the tenant.
 */
@Entity
@Table(name = "contract_bill_adjustment")
@Getter
@Setter
@NoArgsConstructor
public class ContractBillAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    private ContractBill bill;

    @Column(name = "rule_id")
    private UUID ruleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DeductionKind kind;

    @Column(nullable = false, length = 120)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AdjustmentEffect effect;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DeductionBasis basis;

    @Column(precision = 6, scale = 3)
    private BigDecimal rate;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AdjustmentSource source;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
