package org.tornotron.echno_backend.finance.construction.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.tornotron.echno_backend.finance.budget.domain.CostCategory;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "construction_invoice_lines",
        indexes = @Index(name = "idx_cinvl_invoice", columnList = "invoice_id"))
@Getter
@Setter
@NoArgsConstructor
public class ConstructionInvoiceLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private ConstructionInvoice invoice;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(nullable = false, length = 50)
    private String unit;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "tax_rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal taxRate = BigDecimal.ZERO;          // e.g. 18.0000 for 18% GST

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "discount_rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal discountRate = BigDecimal.ZERO;     // e.g. 5.0000 for 5%

    @Column(name = "discount_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotal;                           // quantity * unitPrice

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal total;                              // subtotal + tax - discount

    // Budget head this line consumes, for project cost-control roll-up. Nullable: a line
    // without a head is untagged and does not contribute to any category's spend.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cost_category_id")
    private CostCategory costCategory;

    // Nullable scalar references to core-domain records (no cross-module FK).
    @Column(name = "inventory_item_id")
    private Long inventoryItemId;

    @Column(name = "asset_id")
    private Long assetId;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "line_order", nullable = false)
    private int lineOrder;                                 // zero-based position within the invoice
}
