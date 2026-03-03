package org.tornotron.echno_backend.attendance;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.attendance.enums.ApprovalStatus;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "attendance",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_attendance_employee_date_project",
        columnNames = {"employee_id", "attendance_date", "project_id"}
    )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class Attendance implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "employee_name", nullable = false)
    private String employeeName;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "project_name", nullable = false)
    private String projectName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AttendanceStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_timing_id")
    private ShiftTiming shiftTiming;

    @Builder.Default
    @OneToMany(mappedBy = "attendance", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("eventTimestamp ASC")
    private List<ClockEvent> clockEvents = new ArrayList<>();

    @Column(name = "total_work_minutes")
    private Integer totalWorkMinutes = 0;

    @Column(name = "morning_session_minutes")
    private Integer morningSessionMinutes = 0;

    @Column(name = "afternoon_session_minutes")
    private Integer afternoonSessionMinutes = 0;

    @Column(name = "overtime_minutes")
    private Integer overtimeMinutes = 0;

    @Column(name = "break_duration_minutes")
    private Integer breakDurationMinutes = 0;

    @Builder.Default
    @Column(name = "is_late_arrival", nullable = false)
    private Boolean isLateArrival = false;

    @Builder.Default
    @Column(name = "is_early_checkout", nullable = false)
    private Boolean isEarlyCheckout = false;

    @Builder.Default
    @Column(name = "is_overtime", nullable = false)
    private Boolean isOvertime = false;

    @Column(name = "leave_id")
    private Long leaveId;

    @Column(name = "leave_type")
    private String leaveType;

    @Builder.Default
    @OneToMany(mappedBy = "attendance", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AttendanceRegularization> regularizations = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "attendance", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("startTime ASC")
    private List<MovementRecord> movements = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false)
    private ApprovalStatus approvalStatus = ApprovalStatus.PENDING;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "remarks")
    private String remarks;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
