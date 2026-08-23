package org.tornotron.echno_backend.leave.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "Payload to manually adjust an employee's leave balance under one policy, for "
        + "example a correction or a one-off grant.")
@Data
public class LeaveBalanceAdjustmentDto {

    @Schema(description = "Id of the employee whose balance is being adjusted.", example = "18")
    @NotNull(message = "Employee ID is required")
    private Long employeeId;

    @Schema(description = "Id of the leave policy the balance is tracked under.", example = "3")
    @NotNull(message = "Leave policy ID is required")
    private Long leavePolicyId;

    @Schema(description = "Signed number of days to add or subtract.", example = "2.0")
    @NotNull(message = "Days is required")
    private Double days;

    @Schema(description = "Reason for the adjustment.", example = "Correcting a missed accrual for July 2026")
    @NotBlank(message = "Reason is required")
    @Size(max = 500, message = "Reason must not exceed 500 characters")
    private String reason;

    @Schema(description = "Id of the employee making the adjustment.", example = "2")
    @NotNull(message = "Adjusted by ID is required")
    private Long adjustedById;
}
