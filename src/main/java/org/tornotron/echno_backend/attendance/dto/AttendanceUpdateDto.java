package org.tornotron.echno_backend.attendance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AttendanceUpdateDto {

    @NotNull(message = "Attendance ID is required")
    private Long attendanceId;

    @NotBlank(message = "Reason for correction is required")
    private String correctionReason;

    @NotBlank(message = "Updated location is required")
    private String location;

    @NotBlank(message = "Modified by is required")
    private String modifiedBy;
}
