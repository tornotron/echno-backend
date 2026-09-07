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

/**
 * A clock event as it is served. A field without {@code nullable = true} is one the schema, the
 * mapper or the entity behind it establishes as always present; see
 * {@code ReviewedResponseSchemas} for which is which.
 */
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

    @Schema(description = "Latitude captured for the event. Null where the project does not "
            + "require a location, and null on an event written by a regularization, which "
            + "records a punch that was never taken on a device.", example = "13.0827",
            nullable = true)
    private Double latitude;

    @Schema(description = "Longitude captured for the event. Null under the same conditions as "
            + "latitude, and the two are always absent or present together.", example = "80.2707",
            nullable = true)
    private Double longitude;

    @Schema(description = "GPS accuracy of the captured coordinates, in metres. Null where the "
            + "device sent none, and always null on an event written by a regularization.",
            example = "8.5", nullable = true)
    private Double gpsAccuracy;

    @Schema(description = "Id of the project the event was recorded against.", example = "12")
    private Long projectId;

    @Schema(description = "Name of the project.", example = "Asset Homes Kovilambakkam Phase 2")
    private String projectName;

    @Schema(description = "Platform the event was recorded from. Null where the client sent none, "
            + "and always null on an event written by a regularization.", example = "android",
            nullable = true)
    private String devicePlatform;

    @Schema(description = "Whether the event's coordinates fall within the project's geofence. "
            + "Null when no verdict was reached, which is the case when the project has no "
            + "coordinates, the event carries no position, or the event came from a regularization "
            + "rather than a live punch. Null is not a violation and must not be shown as one.",
            example = "true", nullable = true)
    private Boolean isWithinGeofence;

    @Schema(description = "Distance from the project site at the time of the event, in metres. "
            + "Null when the geofence was not evaluated, which is exactly when isWithinGeofence "
            + "is null. A null is an absent measurement; a zero asserts the punch was taken on "
            + "the marker.", example = "45.0", nullable = true)
    private Double distanceFromProject;

    @Schema(description = "The geofence radius the verdict was reached against, in metres, as it "
            + "stood at the time of the event. Null when the geofence was not evaluated.",
            example = "100", nullable = true)
    private Integer geofenceRadiusMeters;

    @Schema(description = "Why the employee marked their own attendance from outside the site "
            + "boundary. Null when they did not.",
            example = "Working from head office today for the client review", nullable = true)
    private String geofenceExceptionReason;

    @Schema(description = "Employee id of whoever submitted the punch. Differs from the employee "
            + "the record belongs to when a supervisor marked attendance for their team, in which "
            + "case the geofence is not evaluated because the position captured is the "
            + "supervisor's. Null when the caller resolves to no employee in this organization, "
            + "and on an event written by a regularization.",
            example = "42", nullable = true)
    private Long recordedById;

    @Schema(description = "Optional remarks about the event. Null where none were given, and "
            + "always null on an event written by a regularization.",
            example = "Reported directly to the second floor slab pour", nullable = true)
    private String remarks;

    @Schema(description = "Whether this event was added through a regularization request rather than recorded live.", example = "false")
    private Boolean isRegularized;

    @Schema(description = "Reason given if this event was regularized. Null on every punch taken "
            + "live on a device.", example = "Phone battery died before evening clock-out",
            nullable = true)
    private String regularizationReason;

    @Schema(description = "Attachments supporting the event, for example a check-in photo. Empty "
            + "rather than null where no photo was supplied.")
    private List<AttachmentDto> attachments;
}
