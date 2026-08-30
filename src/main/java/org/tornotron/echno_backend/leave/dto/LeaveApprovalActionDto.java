package org.tornotron.echno_backend.leave.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Payload for an approve, reject or delegate action on a leave request.
 *
 * <p>There is no approver field. Whoever is acting is the signed-in caller, resolved from the
 * session in {@code LeaveApprovalService}. It used to be an {@code approverId} the caller sent,
 * compared against the request's current approver, which establishes that the id names the right
 * person and nothing about who sent it: these endpoints are gated on the system-admin and
 * hr-admin roles, so any holder of either could pass the current approver's id and have the
 * decision recorded under that approver's name. Clients that still send {@code approverId} keep
 * working, because a property no payload declares is ignored rather than refused.
 */
@Schema(description = "Payload for an approve, reject or delegate action on a leave request. Who is "
        + "acting is taken from the signed-in session, not from the request.")
@Data
public class LeaveApprovalActionDto {

    @Schema(description = "Comments on the action.", example = "Approved, please plan handover before you leave")
    @Size(max = 1000, message = "Comments must not exceed 1000 characters")
    private String comments;

    @Schema(description = "Id of the employee to delegate the approval to. Required for a delegate action, "
            + "ignored otherwise.", example = "9")
    private Long delegateToId;
}
