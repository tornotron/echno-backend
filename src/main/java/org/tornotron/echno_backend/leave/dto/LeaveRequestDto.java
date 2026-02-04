package org.tornotron.echno_backend.leave.dto;

import lombok.Data;
import org.tornotron.echno_backend.leave.enums.HalfDayType;
import org.tornotron.echno_backend.leave.enums.LeaveStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class LeaveRequestDto {
    private Long id;
    private String requestNumber;
    private Long employeeId;
    private String employeeName;
    private String department;
    private Long organizationId;
    private LeavePolicySimpleDto leavePolicy;
    private LocalDate startDate;
    private HalfDayType startHalfDayType;
    private LocalDate endDate;
    private HalfDayType endHalfDayType;
    private Double totalDays;
    private String reason;
    private LeaveStatus status;
    private Long currentApproverId;
    private String currentApproverName;
    private Integer currentApprovalLevel;
    private Integer maxApprovalLevel;
    private String contactDuringLeave;
    private Long handoverToId;
    private String handoverToName;
    private String handoverNotes;
    private LocalDateTime cancelledAt;
    private String cancellationReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<LeaveApprovalDto> approvals;
}
