package org.tornotron.echno_backend.attendance.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.attendance.enums.MovementType;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Payload to log a movement away from an employee's checked-in location.")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MovementRecordCreationDto {

    @Schema(description = "Id of the attendance record to log this movement against.", example = "781")
    @NotNull
    private Long attendanceId;

    @Schema(description = "Type of movement.", example = "VENDOR_MEETING")
    @NotNull
    private MovementType movementType;

    @Schema(description = "Location the employee is travelling from.", example = "Kovilambakkam Site Office")
    @NotBlank
    private String fromLocation;

    @Schema(description = "Location the employee is travelling to.", example = "Tile Vendor Showroom, Guindy")
    private String toLocation;

    @Schema(
            description = "Site-local wall-clock time the movement started, with no timezone offset. A value carrying an offset or a trailing Z is rejected: the field has no offset to store it in, so accepting one would silently shift the recorded time.",
            example = "2026-01-15T11:00:00")
    @NotNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, lenient = OptBoolean.FALSE)
    private LocalDateTime startTime;

    @Schema(
            description = "Site-local wall-clock time the movement ended, once known, with no timezone offset. A value carrying an offset or a trailing Z is rejected: the field has no offset to store it in, so accepting one would silently shift the recorded time.",
            example = "2026-01-15T12:30:00")
    @JsonFormat(shape = JsonFormat.Shape.STRING, lenient = OptBoolean.FALSE)
    private LocalDateTime endTime;

    @Schema(description = "Purpose of the movement.", example = "Vendor negotiation for tile supply")
    @NotBlank
    private String purpose;

    @Schema(description = "Optional remarks about the movement.", example = "Approved in advance by site supervisor")
    private String remarks;

    @Schema(description = "Latitude at the start of the movement.", example = "12.9166")
    private Double startLatitude;

    @Schema(description = "Longitude at the start of the movement.", example = "80.1998")
    private Double startLongitude;

    @Schema(description = "Latitude at the end of the movement.", example = "13.0604")
    private Double endLatitude;

    @Schema(description = "Longitude at the end of the movement.", example = "80.2496")
    private Double endLongitude;

    @Schema(description = "Distance travelled, in kilometres.", example = "14.5")
    private Double distanceKm;

    @Schema(description = "URLs of supporting attachments, for example a delivery receipt.", example = "[\"https://storage.echno.xyz/movements/220-receipt.jpg\"]")
    private List<String> attachments;
}
