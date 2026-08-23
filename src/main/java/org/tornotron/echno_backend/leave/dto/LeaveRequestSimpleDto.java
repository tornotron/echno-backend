package org.tornotron.echno_backend.leave.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.leave.enums.LeaveStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "Condensed view of a leave request, used in list and summary contexts.")
@Data
public class LeaveRequestSimpleDto {
    @Schema(description = "Id of the leave request.", example = "241")
    private Long id;

    @Schema(description = "Human-readable request number.", example = "LR-2026-0241")
    private String requestNumber;

    @Schema(description = "Name of the employee who raised the request.", example = "Ravi Kumar")
    private String employeeName;

    @Schema(description = "Name of the leave type the request is raised under.", example = "Casual Leave")
    private String leaveTypeName;

    @Schema(description = "First day of leave.", example = "2026-09-14")
    private LocalDate startDate;

    @Schema(description = "Last day of leave.", example = "2026-09-16")
    private LocalDate endDate;

    @Schema(description = "Total leave days, accounting for any half days.", example = "2.5")
    private Double totalDays;

    @Schema(description = "Current status of the request.", example = "PENDING_APPROVAL")
    private LeaveStatus status;

    @Schema(description = "Time the request was created.", example = "2026-08-30T09:15:00")
    private LocalDateTime createdAt;
}
