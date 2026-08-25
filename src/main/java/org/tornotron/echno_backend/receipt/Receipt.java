package org.tornotron.echno_backend.receipt;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A money-received receipt. Type and status are stored as plain strings because the web
 * client sends its own lowercase vocabulary (for example {@code payment}, {@code advance},
 * {@code deposit}, {@code issued}) that is not a valid Java enum identifier; the frontend
 * validates them.
 *
 * <p>The optional links ({@code projectId}, {@code paymentId}, {@code invoiceId},
 * {@code customerId}) are kept as plain nullable columns rather than JPA relationships,
 * keeping the module self-contained and letting a receipt point at rows that may not yet
 * exist.
 */
@Entity
@Table(
        name = "receipts",
        uniqueConstraints = @UniqueConstraint(name = "uk_receipt_number",
                columnNames = {"organization_id", "receipt_number"})
)
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class Receipt implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receipt_number", nullable = false, length = 30)
    private String receiptNumber;

    @Column(name = "type", length = 50)
    private String type;

    @Column(name = "status", length = 50)
    private String status;

    @Column(name = "amount", precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", length = 10)
    private String currency = "INR";

    @Column(name = "receipt_date")
    private LocalDate receiptDate;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(name = "transaction_id")
    private String transactionId;

    @Column(name = "reference_number")
    private String referenceNumber;

    @Column(name = "received_from")
    private String receivedFrom;

    @Column(name = "received_from_address", columnDefinition = "TEXT")
    private String receivedFromAddress;

    @Column(name = "tax_amount", precision = 15, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "tax_rate", precision = 6, scale = 2)
    private BigDecimal taxRate;

    @Column(name = "tax_type", length = 50)
    private String taxType;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "issued_by")
    private Long issuedBy;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(name = "customer_id")
    private Long customerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
