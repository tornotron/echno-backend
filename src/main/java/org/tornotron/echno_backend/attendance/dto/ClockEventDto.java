package org.tornotron.echno_backend.attendance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.attendance.enums.ClockEventType;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClockEventDto {
    private Long id;
    private ClockEventType eventType;
    private LocalDateTime eventTimestamp;
    private Double latitude;
    private Double longitude;
    private Double gpsAccuracy;
    private String photoUrl;
    private Long projectId;
    private String projectName;
    private String devicePlatform;
    private Boolean isWithinGeofence;
    private Double distanceFromProject;
    private String remarks;
    private String verifiedBy;
    private LocalDateTime verifiedAt;
    private Boolean isRegularized;
    private String regularizationReason;
}
