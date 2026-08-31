package org.tornotron.echno_backend.finance.construction.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.finance.construction.ConstructionPayeeType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentMethod;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentVoucherStatus;
import org.tornotron.echno_backend.finance.ledger.domain.BaseEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A construction payment voucher: a single outgoing (or refund) payment record.
 * Distinct from the AR customer-receipt Payment in the ledger module. Unlike the
 * construction invoice it carries no line items. This increment does NO ledger or
 * journal posting: the status is set directly and no JournalEntry is created.
 */
@Entity
@Table(name = "construction_payments",
        uniqueConstraints = @UniqueConstraint(name = "uk_construction_payment_number",
                columnNames = {"organization_id", "payment_number"}),
        indexes = {
                @Index(name = "idx_cpmt_project", columnList = "project_id"),
                @Index(name = "idx_cpmt_vendor", columnList = "vendor_id"),
                @Index(name = "idx_cpmt_status", columnList = "status"),
                @Index(name = "idx_cpmt_type", columnList = "type"),
                @Index(name = "idx_cpmt_payee_type", columnList = "payee_type"),
                @Index(name = "idx_cpmt_payment_date", columnList = "payment_date")
        })
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter @Setter
@NoArgsConstructor
public class ConstructionPayment extends BaseEntity implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payment_number", nullable = false, length = 30)
    private String paymentNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConstructionPaymentType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConstructionPaymentVoucherStatus status = ConstructionPaymentVoucherStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConstructionPaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "payee_type", length = 20)
    private ConstructionPayeeType payeeType;

    // Scalar references to other domains. Kept as plain ids (no cross-module FK) so
    // this module stays additive and decoupled; a later increment can wire them up.
    // project/vendor/purchase-order/employee/labour are core-domain Long ids; the
    // invoice ref points at a construction invoice, whose id is a UUID.
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "invoice_id")
    private UUID invoiceId;

    @Column(name = "purchase_order_id")
    private Long purchaseOrderId;

    @Column(name = "vendor_id")
    private Long vendorId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "sub_contract_id")
    private Long subContractId;

    @Column(name = "labour_id")
    private Long labourId;

    @Column(name = "payee_name", length = 200)
    private String payeeName;

    @Column(name = "payee_details", length = 500)
    private String payeeDetails;

    @Column(precision = 19, scale = 4, nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(nullable = false, length = 10)
    private String currency = "INR";

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Column(name = "transaction_id", length = 100)
    private String transactionId;

    @Column(name = "reference_number", length = 100)
    private String referenceNumber;

    @Column(name = "bank_name", length = 100)
    private String bankName;

    @Column(name = "account_number", length = 50)
    private String accountNumber;

    @Column(name = "ifsc_code", length = 20)
    private String ifscCode;

    // Who raised the voucher and who verified it, both platform user ids stamped from the
    // session by the service (no cross-module FK, matching the submittedBy/approvedBy
    // convention on ConstructionInvoice). raisedBy exists so the verification has a raiser
    // to be compared against: without it the segregation-of-duties rule has nothing to read,
    // and created_by holds an auditing string rather than an id. Vouchers written before this
    // column carry a null and are verified without the comparison, which SelfApprovalPolicy
    // logs.
    @Column(name = "raised_by")
    private Long raisedBy;

    @Column(name = "verified_by")
    private Long verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    /**
     * Why the voucher was voided, written only by the cancel action and never replaced. On a
     * voucher that had been verified this is the only record of why somebody's check was set
     * aside, which is why the action requires it.
     */
    @Column(name = "cancellation_reason", length = 1000)
    private String cancellationReason;

    @Column(length = 1000)
    private String description;

    @Column(length = 1000)
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}
