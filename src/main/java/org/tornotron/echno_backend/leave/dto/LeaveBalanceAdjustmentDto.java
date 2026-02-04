package org.tornotron.echno_backend.leave.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LeaveBalanceAdjustmentDto {

    @NotNull(message = "Employee ID is required")
    private Long employeeId;

    @NotNull(message = "Leave policy ID is required")
    private Long leavePolicyId;

    @NotNull(message = "Days is required")
    private Double days;

    @NotBlank(message = "Reason is required")
    @Size(max = 500, message = "Reason must not exceed 500 characters")
    private String reason;

    @NotNull(message = "Adjusted by ID is required")
    private Long adjustedById;
}
