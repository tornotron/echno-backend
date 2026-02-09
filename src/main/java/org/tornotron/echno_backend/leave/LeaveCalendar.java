package org.tornotron.echno_backend.leave;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.leave.enums.HalfDayType;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@Table(name = "leave_calendar", indexes = {
        @Index(name = "idx_leave_calendar_org_date", columnList = "organization_id, leave_date"),
        @Index(name = "idx_leave_calendar_employee", columnList = "employee_id"),
        @Index(name = "idx_leave_calendar_request", columnList = "leave_request_id"),
        @Index(name = "idx_leave_calendar_date", columnList = "leave_date"),
        @Index(name = "idx_leave_calendar_dept", columnList = "department")
})
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class LeaveCalendar implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leave_request_id", nullable = false)
    private LeaveRequest leaveRequest;

    @Column(name = "leave_date", nullable = false)
    private LocalDate leaveDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_type", nullable = false, length = 20)
    private HalfDayType dayType;

    @Column(name = "leave_type_code", nullable = false, length = 50)
    private String leaveTypeCode;

    @Column(name = "leave_type_name", nullable = false, length = 100)
    private String leaveTypeName;

    @Column(name = "employee_name", nullable = false, length = 100)
    private String employeeName;

    @Column(name = "department", length = 100)
    private String department;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
