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
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One BOQ item on a running account bill. The item's code, unit, contract quantity and rate are
 * copied when the bill is opened, so a later change to the BOQ does not rewrite a bill. The
 * previous quantity is what earlier certified bills accepted on the item; the claimed quantity
 * is what this bill asks for; the measured and accepted quantities are the joint measurement.
 * Owned by its bill, which carries the tenant.
 */
@Entity
@Table(name = "contract_bill_line")
@Getter
@Setter
@NoArgsConstructor
public class ContractBillLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    private ContractBill bill;

    @Column(name = "boq_item_id", nullable = false)
    private UUID boqItemId;

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

    @Column(name = "previous_quantity", nullable = false, precision = 18, scale = 3)
    private BigDecimal previousQuantity;

    @Column(name = "claimed_quantity", nullable = false, precision = 18, scale = 3)
    private BigDecimal claimedQuantity;

    @Column(name = "measured_quantity", precision = 18, scale = 3)
    private BigDecimal measuredQuantity;

    @Column(name = "accepted_quantity", precision = 18, scale = 3)
    private BigDecimal acceptedQuantity;

    @Column(columnDefinition = "TEXT")
    private String remarks;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
