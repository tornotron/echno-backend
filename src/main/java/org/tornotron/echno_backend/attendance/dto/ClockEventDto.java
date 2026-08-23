package org.tornotron.echno_backend.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.attendance.enums.ClockEventType;
import org.tornotron.echno_backend.common.entity.AttachmentDto;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "A single clock event within an attendance record.")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClockEventDto {

    @Schema(description = "Id of the clock event.", example = "3301")
    private Long id;

    @Schema(description = "Type of clock event.", example = "MORNING_CLOCK_IN")
    private ClockEventType eventType;

    @Schema(description = "Timestamp of the event.", example = "2026-01-15T09:02:00")
    private LocalDateTime eventTimestamp;

    @Schema(description = "Latitude captured for the event.", example = "13.0827")
    private Double latitude;

    @Schema(description = "Longitude captured for the event.", example = "80.2707")
    private Double longitude;

    @Schema(description = "GPS accuracy of the captured coordinates, in metres.", example = "8.5")
    private Double gpsAccuracy;

    @Schema(description = "URL of the photo captured with the event, if any.", example = "https://storage.echno.xyz/clock-events/3301-in.jpg")
    private String photoUrl;

    @Schema(description = "Id of the project the event was recorded against.", example = "12")
    private Long projectId;

    @Schema(description = "Name of the project.", example = "Asset Homes Kovilambakkam Phase 2")
    private String projectName;

    @Schema(description = "Platform the event was recorded from.", example = "android")
    private String devicePlatform;

    @Schema(description = "Whether the event's coordinates fall within the project's geofence.", example = "true")
    private Boolean isWithinGeofence;

    @Schema(description = "Distance from the project site at the time of the event, in metres.", example = "45.0")
    private Double distanceFromProject;

    @Schema(description = "Optional remarks about the event.", example = "Reported directly to the second floor slab pour")
    private String remarks;

    @Schema(description = "Name or id of the person who verified the event, if regularized.", example = "Anand Rajashekar")
    private String verifiedBy;

    @Schema(description = "Timestamp the event was verified.", example = "2026-01-16T11:00:00")
    private LocalDateTime verifiedAt;

    @Schema(description = "Whether this event was added through a regularization request rather than recorded live.", example = "false")
    private Boolean isRegularized;

    @Schema(description = "Reason given if this event was regularized.", example = "Phone battery died before evening clock-out")
    private String regularizationReason;

    @Schema(description = "Attachments supporting the event, for example a check-in photo.")
    private List<AttachmentDto> attachments;
}
