package org.tornotron.echno_backend.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

@Schema(description = "Payload to regularize a day by its date. The server finds the employee's "
        + "attendance record for the date and project, creating it when there is none, and files "
        + "the regularization request against it in the same step.")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegularizationByDateRequestDto {

    @Schema(description = "Employee the request is for: the signed-in employee, or anyone when "
            + "the caller holds an attendance record-management role.", example = "18")
    @NotNull
    private Long employeeId;

    @Schema(description = "Project the day was worked on.", example = "12")
    @NotNull
    private Long projectId;

    @Schema(description = "The day to regularize. Must not be after today.", example = "2026-08-27")
    @NotNull
    private LocalDate attendanceDate;

    @Schema(description = "Why the attendance is missing.", example = "Phone battery died before clocking in")
    @NotBlank
    @Size(max = 1000)
    private String reason;

    @Schema(description = "Site-local clock-in time the employee asks for.", example = "09:05:00",
            type = "string", format = "time")
    @NotNull
    private LocalTime clockInTime;

    @Schema(description = "Site-local clock-out time the employee asks for, when known. Must be "
            + "after the clock-in time.", example = "18:10:00", type = "string", format = "time",
            nullable = true)
    private LocalTime clockOutTime;
}
