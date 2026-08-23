package org.tornotron.echno_backend.leave;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Rules for one leave type within an organization: entitlement, accrual, and request limits.
 *
 * <p>Defines the annual quota and monthly accrual rate, carry-forward limit and expiry,
 * per-request minimums and maximums, advance-notice and attachment requirements, and eligibility
 * gates (applicable genders, minimum service months). Policies are deactivated rather than deleted
 * so historical balances and requests keep their reference.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "leave_policy", indexes = {
        @Index(name = "idx_leave_policy_org", columnList = "organization_id"),
        @Index(name = "idx_leave_policy_type_code", columnList = "leave_type_code"),
        @Index(name = "idx_leave_policy_active", columnList = "is_active")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_leave_policy_org_type", columnNames = {"organization_id", "leave_type_code"})
})
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class LeavePolicy implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "leave_type_code", nullable = false, length = 50)
    private String leaveTypeCode;

    @Column(name = "leave_type_name", nullable = false, length = 100)
    private String leaveTypeName;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "annual_quota", nullable = false)
    private Double annualQuota;

    @Column(name = "accrual_rate_per_month")
    private Double accrualRatePerMonth;

    @Column(name = "carry_forward_limit")
    private Double carryForwardLimit;

    @Column(name = "carry_forward_expiry_months")
    private Integer carryForwardExpiryMonths;

    @Column(name = "min_days_per_request")
    private Double minDaysPerRequest = 0.5;

    @Column(name = "max_days_per_request")
    private Double maxDaysPerRequest;

    @Column(name = "advance_notice_days")
    private Integer advanceNoticeDays = 0;

    @Column(name = "requires_attachment")
    private Boolean requiresAttachment = false;

    @Column(name = "attachment_required_after_days")
    private Integer attachmentRequiredAfterDays;

    @Column(name = "applicable_genders", length = 50)
    private String applicableGenders = "ALL";

    @Column(name = "min_service_months")
    private Integer minServiceMonths = 0;

    @Column(name = "allow_half_day")
    private Boolean allowHalfDay = true;

    @Column(name = "is_paid")
    private Boolean isPaid = true;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "display_order")
    private Integer displayOrder = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "leavePolicy")
    private List<LeaveBalance> leaveBalances = new ArrayList<>();

    @OneToMany(mappedBy = "leavePolicy")
    private List<LeaveRequest> leaveRequests = new ArrayList<>();
}
