package org.tornotron.echno_backend.finance.construction.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceStatus;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentStatus;
import org.tornotron.echno_backend.finance.ledger.domain.BaseEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "construction_invoices",
        uniqueConstraints = @UniqueConstraint(name = "uk_construction_invoice_number",
                columnNames = {"organization_id", "invoice_number"}),
        indexes = {
                @Index(name = "idx_cinv_project", columnList = "project_id"),
                @Index(name = "idx_cinv_vendor", columnList = "vendor_id"),
                @Index(name = "idx_cinv_status", columnList = "status"),
                @Index(name = "idx_cinv_type", columnList = "type"),
                @Index(name = "idx_cinv_issue_date", columnList = "issue_date")
        })
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter @Setter
@NoArgsConstructor
public class ConstructionInvoice extends BaseEntity implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "invoice_number", nullable = false, length = 30)
    private String invoiceNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConstructionInvoiceType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConstructionInvoiceStatus status = ConstructionInvoiceStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private ConstructionPaymentStatus paymentStatus = ConstructionPaymentStatus.UNPAID;

    // Scalar references to core-domain records. Kept as plain ids (no cross-module FK)
    // so this module stays additive and decoupled; a later increment can wire them up.
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "vendor_id")
    private Long vendorId;

    @Column(name = "purchase_order_id")
    private Long purchaseOrderId;

    @Column(name = "goods_receipt_id")
    private Long goodsReceiptId;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    @Column(precision = 19, scale = 4, nullable = false)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "tax_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "discount_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "paid_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(name = "balance_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal balanceAmount = BigDecimal.ZERO;

    @Column(name = "payment_terms", length = 100)
    private String paymentTerms;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "gst_number", length = 30)
    private String gstNumber;

    @Column(name = "tax_type", length = 20)
    private String taxType;

    @Column(length = 1000)
    private String notes;

    @Column(name = "terms_and_conditions", length = 2000)
    private String termsAndConditions;

    // Approval workflow audit trail. Set by the service as the invoice moves through
    // submit -> approve; ids are core-domain user ids (no cross-module FK), matching the
    // verifiedBy/verifiedAt convention on ConstructionPayment.
    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "payment_recorded_by")
    private Long paymentRecordedBy;

    // Links to the ledger. The journal entry posted when the invoice is approved and,
    // on cancel, the reversal that unwinds it. Kept as plain ids (no cross-module FK).
    @Column(name = "journal_entry_id")
    private UUID journalEntryId;

    @Column(name = "reversal_journal_entry_id")
    private UUID reversalJournalEntryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("lineOrder ASC")
    private List<ConstructionInvoiceLine> lines = new ArrayList<>();

    public void addLine(ConstructionInvoiceLine line) {
        line.setInvoice(this);
        line.setLineOrder(this.lines.size());
        this.lines.add(line);
    }
}
