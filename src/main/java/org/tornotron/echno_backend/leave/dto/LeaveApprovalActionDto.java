package org.tornotron.echno_backend.leave.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "Payload for an approve, reject or delegate action on a leave request.")
@Data
public class LeaveApprovalActionDto {

    @Schema(description = "Id of the employee performing the action.", example = "5")
    @NotNull(message = "Approver ID is required")
    private Long approverId;

    @Schema(description = "Comments on the action.", example = "Approved, please plan handover before you leave")
    @Size(max = 1000, message = "Comments must not exceed 1000 characters")
    private String comments;

    @Schema(description = "Id of the employee to delegate the approval to. Required for a delegate action, "
            + "ignored otherwise.", example = "9")
    private Long delegateToId;
}
