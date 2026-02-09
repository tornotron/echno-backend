package org.tornotron.echno_backend.leave;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.leave.enums.HalfDayType;
import org.tornotron.echno_backend.leave.enums.LeaveStatus;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Table(name = "leave_request", indexes = {
        @Index(name = "idx_leave_request_employee", columnList = "employee_id"),
        @Index(name = "idx_leave_request_org", columnList = "organization_id"),
        @Index(name = "idx_leave_request_policy", columnList = "leave_policy_id"),
        @Index(name = "idx_leave_request_status", columnList = "status"),
        @Index(name = "idx_leave_request_dates", columnList = "start_date, end_date"),
        @Index(name = "idx_leave_request_approver", columnList = "current_approver_id")
})
public class LeaveRequest implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "request_number", nullable = false, unique = true, length = 50)
    private String requestNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leave_policy_id", nullable = false)
    private LeavePolicy leavePolicy;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "start_half_day_type", length = 20)
    private HalfDayType startHalfDayType;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_half_day_type", length = 20)
    private HalfDayType endHalfDayType;

    @Column(name = "total_days", nullable = false)
    private Double totalDays;

    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private LeaveStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_approver_id")
    private Employee currentApprover;

    @Column(name = "current_approval_level")
    private Integer currentApprovalLevel;

    @Column(name = "max_approval_level")
    private Integer maxApprovalLevel;

    @Column(name = "contact_during_leave", length = 100)
    private String contactDuringLeave;

    @Column(name = "handover_to_id")
    private Long handoverToId;

    @Column(name = "handover_notes", length = 500)
    private String handoverNotes;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "leaveRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("approvalLevel ASC")
    private List<LeaveApproval> approvals = new ArrayList<>();

    @OneToMany(mappedBy = "leaveRequest", cascade = CascadeType.ALL)
    @OrderBy("createdAt ASC")
    private List<LeaveTransaction> transactions = new ArrayList<>();

    @OneToMany(mappedBy = "leaveRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LeaveCalendar> calendarEntries = new ArrayList<>();
}
