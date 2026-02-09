package org.tornotron.echno_backend.leave;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.employee.Employee;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
@Table(name = "leave_balance", indexes = {
        @Index(name = "idx_leave_balance_employee", columnList = "employee_id"),
        @Index(name = "idx_leave_balance_policy", columnList = "leave_policy_id"),
        @Index(name = "idx_leave_balance_year", columnList = "year")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_leave_balance_emp_policy_year", columnNames = {"employee_id", "leave_policy_id", "year"})
})
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class LeaveBalance implements TenantScopedEntity {

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
    @JoinColumn(name = "leave_policy_id", nullable = false)
    private LeavePolicy leavePolicy;

    @Column(name = "year", nullable = false)
    private Integer year;

    @Column(name = "opening_balance", nullable = false)
    private Double openingBalance = 0.0;

    @Column(name = "accrued", nullable = false)
    private Double accrued = 0.0;

    @Column(name = "used", nullable = false)
    private Double used = 0.0;

    @Column(name = "pending", nullable = false)
    private Double pending = 0.0;

    @Column(name = "carry_forward_from_previous")
    private Double carryForwardFromPrevious = 0.0;

    @Column(name = "carry_forward_expiry_date")
    private LocalDate carryForwardExpiryDate;

    @Column(name = "last_calculated_at")
    private LocalDateTime lastCalculatedAt;

    @Column(name = "last_calculation_month")
    private Integer lastCalculationMonth;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "leaveBalance")
    private List<LeaveTransaction> transactions = new ArrayList<>();

    @Transient
    public Double getAvailableBalance() {
        return openingBalance + accrued - used;
    }

    @Transient
    public Double getBookableBalance() {
        return getAvailableBalance() - pending;
    }
}
