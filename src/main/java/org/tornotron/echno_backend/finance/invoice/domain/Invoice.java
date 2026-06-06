package org.tornotron.echno_backend.finance.invoice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.tornotron.echno_backend.finance.invoice.InvoiceStatus;
import org.tornotron.echno_backend.finance.ledger.domain.BaseEntity;
import org.tornotron.echno_backend.finance.ledger.domain.Customer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "invoices",
        uniqueConstraints = @UniqueConstraint(name = "uk_invoice_number", columnNames = "invoice_number"),
        indexes = {
                @Index(name = "idx_invoice_customer", columnList = "customer_id"),
                @Index(name = "idx_invoice_status", columnList = "status"),
                @Index(name = "idx_invoice_date", columnList = "invoice_date")
        })
@Getter @Setter
@NoArgsConstructor
public class Invoice extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "invoice_number", nullable = false, length = 30)
    private String invoiceNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    @Column(precision = 19, scale = 4, nullable = false)
    private BigDecimal subtotal  = BigDecimal.ZERO;

    @Column(name = "tax_total", precision = 19, scale = 4, nullable = false)
    private BigDecimal taxTotal  = BigDecimal.ZERO;

    @Column(precision = 19, scale = 4, nullable = false)
    private BigDecimal total     = BigDecimal.ZERO;

    @Column(name = "amount_paid", precision = 19, scale = 4, nullable = false)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Column(name = "journal_entry_id")
    private UUID journalEntryId;             // JE created on issue

    @Column(name = "reversal_je_id")
    private UUID reversalJournalEntryId;     // JE created on cancel (after issue)

    @Column(length = 500)
    private String notes;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderColumn(name = "line_order")
    private List<InvoiceLine> lines = new ArrayList<>();

    public void addLine(InvoiceLine line) {
        line.setInvoice(this);
        this.lines.add(line);
    }

    public BigDecimal balanceDue() {
        return total.subtract(amountPaid);
    }
}
