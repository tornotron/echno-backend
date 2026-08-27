package org.tornotron.echno_backend.attendance.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.attendance.enums.ClockEventType;

import java.time.LocalDateTime;

@Schema(description = "A single corrected clock event submitted as part of a regularization request.")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClockEventCreationDto {

    @Schema(description = "Type of clock event.", example = "EVENING_CLOCK_OUT")
    @NotNull
    private ClockEventType eventType;

    @Schema(
            description = "Site-local wall-clock time of the event, with no timezone offset. A value carrying an offset or a trailing Z is rejected: the field has no offset to store it in, so accepting one would silently shift the recorded time and, near midnight, the attendance date with it.",
            example = "2026-01-15T18:00:00")
    @NotNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, lenient = OptBoolean.FALSE)
    private LocalDateTime eventTimestamp;

    @Schema(description = "Latitude captured for the event.", example = "13.0827")
    private Double latitude;

    @Schema(description = "Longitude captured for the event.", example = "80.2707")
    private Double longitude;

    @Schema(description = "GPS accuracy of the captured coordinates, in metres.", example = "8.5")
    private Double gpsAccuracy;

    @Schema(description = "Altitude captured for the event, in metres.", example = "12.0")
    private Double altitude;

    @Schema(description = "URL of the photo captured with the event, if any.", example = "https://storage.echno.xyz/clock-events/781-out.jpg")
    private String photoUrl;

    @Schema(description = "Id of the project the event was recorded against.", example = "12")
    @NotNull
    private Long projectId;

    @Schema(description = "Platform the event was recorded from.", example = "android")
    private String devicePlatform;

    @Schema(description = "Identifier of the device used to record the event.", example = "device-8f3a21")
    private String deviceId;

    @Schema(description = "IP address of the device at the time of the event.", example = "10.24.6.41")
    private String ipAddress;

    @Schema(description = "Optional remarks about the corrected event.", example = "Forgot to clock out before leaving site")
    private String remarks;
}
