package org.tornotron.echno_backend.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.attendance.enums.ClockEventType;

import java.time.LocalDateTime;

@Schema(description = "Payload to record a clock event against an existing attendance record.")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceClockEventDto {

    @Schema(description = "Id of the attendance record the event belongs to.", example = "781")
    @NotNull
    private Long attendanceId;

    @Schema(description = "Type of clock event.", example = "LUNCH_BREAK_START")
    @NotNull
    private ClockEventType eventType;

    @Schema(description = "Timestamp of the event.", example = "2026-01-15T13:00:00")
    @NotNull
    private LocalDateTime eventTimestamp;

    @Schema(description = "Latitude captured for the event.", example = "13.0827")
    private Double latitude;

    @Schema(description = "Longitude captured for the event.", example = "80.2707")
    private Double longitude;

    @Schema(description = "GPS accuracy of the captured coordinates, in metres.", example = "8.5")
    private Double gpsAccuracy;

    @Schema(description = "Altitude captured for the event, in metres.", example = "12.0")
    private Double altitude;

    @Schema(description = "URL of the photo captured with the event, if any.", example = "https://storage.echno.xyz/clock-events/781-lunch.jpg")
    private String photoUrl;

    @Schema(description = "Platform the event was recorded from.", example = "android")
    private String devicePlatform;

    @Schema(description = "Identifier of the device used to record the event.", example = "device-8f3a21")
    private String deviceId;

    @Schema(description = "IP address of the device at the time of the event.", example = "10.24.6.41")
    private String ipAddress;

    @Schema(description = "Optional remarks about the event.", example = "Left site briefly for a material delivery")
    private String remarks;
}
