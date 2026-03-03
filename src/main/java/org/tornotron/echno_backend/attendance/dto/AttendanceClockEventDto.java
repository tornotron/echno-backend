package org.tornotron.echno_backend.attendance.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.attendance.enums.ClockEventType;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceClockEventDto {

    @NotNull
    private Long attendanceId;

    @NotNull
    private ClockEventType eventType;

    @NotNull
    private LocalDateTime eventTimestamp;

    private Double latitude;
    private Double longitude;
    private Double gpsAccuracy;
    private Double altitude;
    private String photoUrl;
    private String devicePlatform;
    private String deviceId;
    private String ipAddress;
    private String remarks;
}
