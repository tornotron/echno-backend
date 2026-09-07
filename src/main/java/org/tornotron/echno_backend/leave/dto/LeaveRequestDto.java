package org.tornotron.echno_backend.leave.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.leave.enums.HalfDayType;
import org.tornotron.echno_backend.leave.enums.LeaveStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "A leave request with its dates, current approval state and approval trail.")
@Data
public class LeaveRequestDto {
    @Schema(description = "Id of the leave request.", example = "241")
    private Long id;

    @Schema(description = "Human-readable request number.", example = "LR-2026-0241")
    private String requestNumber;

    @Schema(description = "Id of the employee who raised the request.", example = "18")
    private Long employeeId;

    @Schema(description = "Name of the employee who raised the request.", example = "Ravi Kumar")
    private String employeeName;

    @Schema(nullable = true, description = "Department of the employee who raised the request. Read from "
            + "the employee record, which the employee table permits to carry no department.", example = "Civil")
    private String department;

    @Schema(description = "Id of the organization the employee belongs to.", example = "2")
    private Long organizationId;

    @Schema(description = "Leave policy the request is raised under.")
    private LeavePolicySimpleDto leavePolicy;

    @Schema(description = "First day of leave.", example = "2026-09-14")
    private LocalDate startDate;

    @Schema(nullable = true, description = "Whether the start date is a full day or a half day. Null where "
            + "the requester did not mark the first day as a half day; the column is written only when they "
            + "did.", example = "FULL_DAY")
    private HalfDayType startHalfDayType;

    @Schema(description = "Last day of leave.", example = "2026-09-16")
    private LocalDate endDate;

    @Schema(nullable = true, description = "Whether the end date is a full day or a half day. Null where "
            + "the requester did not mark the last day as a half day; the column is written only when they "
            + "did.", example = "SECOND_HALF")
    private HalfDayType endHalfDayType;

    @Schema(description = "Total leave days, accounting for any half days.", example = "2.5")
    private Double totalDays;

    @Schema(description = "Reason for the leave.", example = "Attending sister's wedding in Coimbatore")
    private String reason;

    @Schema(description = "Current status of the request.", example = "PENDING_APPROVAL")
    private LeaveStatus status;

    @Schema(nullable = true, description = "Id of the employee who must act next in the approval chain. "
            + "Never set on a draft, and cleared once the request reaches a final state (approved, rejected, "
            + "cancelled or withdrawn).", example = "5")
    private Long currentApproverId;

    @Schema(nullable = true, description = "Name of the employee who must act next in the approval chain. "
            + "Null whenever currentApproverId is, since it is read from that employee.", example = "Anitha Menon")
    private String currentApproverName;

    @Schema(nullable = true, description = "The approval level currently pending action. Set when the "
            + "approval chain is built at submission, so it is null on a draft and on a request that was "
            + "finalized immediately because no approver could be resolved for the employee.", example = "1")
    private Integer currentApprovalLevel;

    @Schema(nullable = true, description = "The highest approval level configured for this request. "
            + "Written alongside currentApprovalLevel when the chain is built, and null wherever that one "
            + "is.", example = "2")
    private Integer maxApprovalLevel;

    @Schema(nullable = true, description = "Contact number or address reachable during the leave. Null "
            + "where the requester supplied none.", example = "9847012345")
    private String contactDuringLeave;

    @Schema(nullable = true, description = "Id of the employee handling handover during the leave. Null "
            + "where no handover was arranged.", example = "12")
    private Long handoverToId;

    @Schema(nullable = true, description = "Name of the employee handling handover during the leave. "
            + "Filled in only by the single-request read; every list response, and every response returned "
            + "by a create, update or approval action, leaves it null even when handoverToId is set.",
            example = "Deepak Nair")
    private String handoverToName;

    @Schema(nullable = true, description = "Notes for the person receiving the handover. Null where none "
            + "were written.", example = "Site inspection at "
            + "Asset Homes Chennai is due on 2026-09-15, please coordinate with the QA team")
    private String handoverNotes;

    @Schema(nullable = true, description = "Time the request was cancelled. Null until the request is "
            + "cancelled.", example = "2026-09-10T11:20:00")
    private LocalDateTime cancelledAt;

    @Schema(nullable = true, description = "Reason the request was cancelled. Null until the request is "
            + "cancelled, and on a cancellation recorded without a reason.",
            example = "Wedding postponed to next month")
    private String cancellationReason;

    @Schema(description = "Time the request was created.", example = "2026-08-30T09:15:00")
    private LocalDateTime createdAt;

    @Schema(description = "Time the request was last updated.", example = "2026-09-01T14:05:00")
    private LocalDateTime updatedAt;

    @Schema(description = "Approval actions recorded against this request, in the order they occurred.")
    private List<LeaveApprovalDto> approvals;
}
