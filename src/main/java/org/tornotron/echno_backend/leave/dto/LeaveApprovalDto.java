package org.tornotron.echno_backend.leave.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.leave.enums.ApprovalAction;

import java.time.LocalDateTime;

@Schema(description = "One approval action recorded against a leave request.")
@Data
public class LeaveApprovalDto {
    @Schema(description = "Id of the approval record.", example = "612")
    private Long id;

    @Schema(description = "Id of the leave request this action was taken on.", example = "241")
    private Long leaveRequestId;

    @Schema(description = "Id of the employee who took the action.", example = "5")
    private Long approverId;

    @Schema(description = "Name of the employee who took the action.", example = "Anitha Menon")
    private String approverName;

    @Schema(nullable = true, description = "Job title of the employee who took the action. Null where the "
            + "employee record carries no designation, which the employee table permits.", example = "Site Manager")
    private String approverDesignation;

    @Schema(description = "Approval level this action was taken at.", example = "1")
    private Integer approvalLevel;

    @Schema(description = "The action taken.", example = "APPROVED")
    private ApprovalAction action;

    @Schema(nullable = true, description = "Comments on the action. Null where the approver recorded none, "
            + "and on the pending rows written when the approval chain is built, which carry no comment yet.",
            example = "Approved, please plan handover before you leave")
    private String comments;

    @Schema(nullable = true, description = "Id of the approver this action was delegated from. Null unless "
            + "the action reached this approver by delegation.", example = "3")
    private Long delegatedFromId;

    @Schema(nullable = true, description = "Name of the approver this action was delegated from. Always null "
            + "on this response: the mapper leaves the field unset and nothing else fills it in, so resolve the "
            + "name from delegatedFromId.", example = "Suresh Pillai")
    private String delegatedFromName;

    @Schema(nullable = true, description = "Time the action was taken. Null while the approval is still "
            + "pending, since the column is written only when the approver acts.", example = "2026-08-31T10:05:00")
    private LocalDateTime actionAt;

    @Schema(description = "Time this approval record was created.", example = "2026-08-31T10:05:00")
    private LocalDateTime createdAt;
}
