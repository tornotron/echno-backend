package org.tornotron.echno_backend.leave;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.leave.enums.TransactionType;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * An immutable ledger entry recording a single change to a leave balance.
 *
 * <p>Captures the transaction type (accrual, deduction, adjustment, and so on), the signed number
 * of days, and the balance before and after, giving an auditable history of how a balance reached
 * its current value. Linked to the balance it moved and, where applicable, the originating request.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "leave_transaction", indexes = {
        @Index(name = "idx_leave_txn_employee", columnList = "employee_id"),
        @Index(name = "idx_leave_txn_balance", columnList = "leave_balance_id"),
        @Index(name = "idx_leave_txn_request", columnList = "leave_request_id"),
        @Index(name = "idx_leave_txn_date", columnList = "transaction_date"),
        @Index(name = "idx_leave_txn_type", columnList = "transaction_type")
})
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class LeaveTransaction implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leave_balance_id", nullable = false)
    private LeaveBalance leaveBalance;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leave_request_id")
    private LeaveRequest leaveRequest;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 30)
    private TransactionType transactionType;

    @Column(name = "days", nullable = false)
    private Double days;

    @Column(name = "balance_before", nullable = false)
    private Double balanceBefore;

    @Column(name = "balance_after", nullable = false)
    private Double balanceAfter;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(name = "reference_month")
    private Integer referenceMonth;

    @Column(name = "reference_year")
    private Integer referenceYear;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "created_by_id")
    private Long createdById;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
