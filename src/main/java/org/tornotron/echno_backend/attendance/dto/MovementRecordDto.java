package org.tornotron.echno_backend.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.attendance.enums.MovementType;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "A record of an employee travelling away from their checked-in location during a work day.")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MovementRecordDto {

    @Schema(description = "Id of the movement record.", example = "220")
    private Long id;

    @Schema(description = "Id of the attendance record this movement was logged against.", example = "781")
    private Long attendanceId;

    @Schema(description = "Id of the employee.", example = "42")
    private Long employeeId;

    @Schema(description = "Full name of the employee.", example = "Ravi Kumar")
    private String employeeName;

    @Schema(description = "Type of movement.", example = "SITE_TRAVEL")
    private MovementType movementType;

    @Schema(description = "Location the employee travelled from.", example = "Kovilambakkam Site Office")
    private String fromLocation;

    @Schema(description = "Location the employee travelled to.", example = "Asset Homes Corporate Office, Chennai")
    private String toLocation;

    @Schema(description = "Timestamp the movement started.", example = "2026-01-15T11:00:00")
    private LocalDateTime startTime;

    @Schema(description = "Timestamp the movement ended.", example = "2026-01-15T12:30:00")
    private LocalDateTime endTime;

    @Schema(description = "Duration of the movement in minutes.", example = "90")
    private Integer durationMinutes;

    @Schema(description = "Distance travelled, in kilometres.", example = "14.5")
    private Double distanceKm;

    @Schema(description = "Purpose of the movement.", example = "Vendor negotiation for tile supply")
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

    @Schema(description = "URLs of supporting attachments, for example a delivery receipt.", example = "[\"https://storage.echno.xyz/movements/220-receipt.jpg\"]")
    private List<String> attachments;

    @Schema(description = "Name of the person who verified the movement, taken from their session.", example = "Anand Rajashekar")
    private String verifiedBy;

    @Schema(description = "Employee id of the person who verified the movement, null where the verifier has no employee record in this organization.", example = "17", nullable = true)
    private Long verifiedById;

    @Schema(description = "Timestamp the movement was verified.", example = "2026-01-16T09:00:00")
    private LocalDateTime verifiedAt;

    @Schema(description = "Whether the movement has been verified.", example = "false")
    private Boolean isVerified;

    @Schema(description = "Timestamp the movement record was created.", example = "2026-01-15T11:05:00")
    private LocalDateTime createdAt;

    @Schema(description = "Timestamp the movement record was last updated.", example = "2026-01-15T12:35:00")
    private LocalDateTime updatedAt;
}
