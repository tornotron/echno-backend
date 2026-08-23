package org.tornotron.echno_backend.leave.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "An employee's leave balances across all policies for one year, with totals.")
@Data
public class LeaveBalanceSummaryDto {
    @Schema(description = "Id of the employee this summary belongs to.", example = "18")
    private Long employeeId;

    @Schema(description = "Name of the employee this summary belongs to.", example = "Ravi Kumar")
    private String employeeName;

    @Schema(description = "Calendar year this summary applies to.", example = "2026")
    private Integer year;

    @Schema(description = "Per-policy balances making up this summary.")
    private List<LeaveBalanceDto> balances;

    @Schema(description = "Total days available across all policies.", example = "14.5")
    private Double totalAvailable;

    @Schema(description = "Total days used across all policies.", example = "6.0")
    private Double totalUsed;

    @Schema(description = "Total days pending across all policies.", example = "1.0")
    private Double totalPending;
}
