package org.tornotron.echno_backend.modules.workprogress.billing.payment;

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
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * One attempt to pay an approved bill: an amount up to what is due on its payable, the two
 * signatures, what carried it and how it ended. The payable stays the record of what was paid; this
 * row is how a payment got there. Its id is the idempotency key sent to a provider, so sending it
 * again cannot pay twice.
 */
@Entity
@Table(name = "contract_bill_payment")
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class ContractBillPayment implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "bill_id", nullable = false)
    private UUID billId;

    @Column(name = "payable_id", nullable = false)
    private Long payableId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    /** What actually went onto the payable when it settled; less than the amount only when reconciliation is owed. */
    @Column(name = "applied_amount", precision = 18, scale = 2)
    private BigDecimal appliedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BillPaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BillPaymentProvider provider;

    @Column(name = "provider_ref", length = 100)
    private String providerRef;

    /** The bank's reference for the transfer (UTR). */
    @Column(name = "bank_reference", length = 100)
    private String bankReference;

    @Column(name = "paid_on")
    private LocalDate paidOn;

    @Column(name = "beneficiary_name", length = 200)
    private String beneficiaryName;

    @Column(name = "beneficiary_ifsc", length = 20)
    private String beneficiaryIfsc;

    @Column(name = "beneficiary_account_last4", length = 4)
    private String beneficiaryAccountLast4;

    @Column(columnDefinition = "TEXT")
    private String note;

    /** Why it failed or was rejected. */
    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    /** Why the last attempt to reach the provider did not get an answer. Cleared by the next answer. */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "authorized_by")
    private Long authorizedBy;

    @Column(name = "authorized_at")
    private LocalDateTime authorizedAt;

    @Column(name = "self_authorized", nullable = false)
    private boolean selfAuthorized;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private int version;
}
