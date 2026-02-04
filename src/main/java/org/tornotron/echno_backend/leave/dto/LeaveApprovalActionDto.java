package org.tornotron.echno_backend.leave.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LeaveApprovalActionDto {

    @NotNull(message = "Approver ID is required")
    private Long approverId;

    @Size(max = 1000, message = "Comments must not exceed 1000 characters")
    private String comments;

    private Long delegateToId;
}
