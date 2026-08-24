package org.tornotron.echno_backend.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Schema(description = "Payload to record an employee's first clock event of the day.")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceCheckInDto {

    @Schema(description = "Id of the employee checking in.", example = "42")
    @NotNull
    private Long employeeId;

    @Schema(description = "Id of the project the employee is checking in against.", example = "12")
    @NotNull
    private Long projectId;

    @Schema(description = "Id of the shift the employee is working. Optional: when omitted, the "
            + "employee's assigned shift is used. Required only if the employee has no assigned shift.",
            example = "5")
    private Long shiftTimingId;

    @Schema(description = "Timestamp of the check-in event.", example = "2026-01-15T09:02:00")
    @NotNull
    private LocalDateTime eventTimestamp;

    @Schema(description = "Latitude captured at check-in.", example = "13.0827")
    private Double latitude;

    @Schema(description = "Longitude captured at check-in.", example = "80.2707")
    private Double longitude;

    @Schema(description = "GPS accuracy of the captured coordinates, in metres.", example = "8.5")
    private Double gpsAccuracy;

    @Schema(description = "Altitude captured at check-in, in metres.", example = "12.0")
    private Double altitude;

    @Schema(description = "Platform the check-in was made from.", example = "android")
    private String devicePlatform;

    @Schema(description = "Identifier of the device used to check in.", example = "device-8f3a21")
    private String deviceId;

    @Schema(description = "IP address of the device at check-in.", example = "10.24.6.41")
    private String ipAddress;

    @Schema(description = "Optional remarks about the check-in.", example = "Reported directly to the second floor slab pour")
    private String remarks;
}
