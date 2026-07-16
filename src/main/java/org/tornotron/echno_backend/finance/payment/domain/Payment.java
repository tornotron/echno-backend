package org.tornotron.echno_backend.finance.payment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.finance.bank.domain.CompanyBankAccount;
import org.tornotron.echno_backend.finance.ledger.domain.Account;
import org.tornotron.echno_backend.finance.ledger.domain.BaseEntity;
import org.tornotron.echno_backend.finance.ledger.domain.Customer;
import org.tornotron.echno_backend.organization.Organization;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "payments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_number", columnNames = {"organization_id", "payment_number"}),
                @UniqueConstraint(name = "uk_payment_idem",   columnNames = {"organization_id", "idempotency_key"})
        },
        indexes = {
                @Index(name = "idx_payment_customer", columnList = "customer_id"),
                @Index(name = "idx_payment_date",     columnList = "payment_date")
        })
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter @Setter
@NoArgsConstructor
public class Payment extends BaseEntity implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payment_number",nullable = false, length = 30)
    private String paymentNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_bank_account_id", nullable = false)
    private CompanyBankAccount companyBankAccount;

    @Column(name = "external_reference", length = 100)
    private String externalReference;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(name = "journal_entry_id")
    private UUID journalEntryId;

    @Column(length = 500)
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true,
        fetch = FetchType.LAZY)
    private List<PaymentAllocation> allocations = new ArrayList<>();

    public void addAllocation(PaymentAllocation a) {
        a.setPayment(this);
        this.allocations.add(a);
    }
}
