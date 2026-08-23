package org.tornotron.echno_backend.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.attendance.enums.RegularizationStatus;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "A request to correct an attendance record that is missing one or more clock events.")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceRegularizationDto {

    @Schema(description = "Id of the regularization request.", example = "56")
    private Long id;

    @Schema(description = "Id of the attendance record being corrected.", example = "781")
    private Long attendanceId;

    @Schema(description = "Reason given for the missing events.", example = "Phone battery died before evening clock-out")
    private String reason;

    @Schema(description = "Name or id of the person who submitted the request.", example = "Ravi Kumar")
    private String requestedBy;

    @Schema(description = "Id of the employee who submitted the request, stored alongside the name so "
            + "regularizations can be filtered by requester.", example = "18")
    private Long requestedById;

    @Schema(description = "Timestamp the request was submitted.", example = "2026-01-16T08:30:00")
    private LocalDateTime requestedAt;

    @Schema(description = "Name or id of the person who approved or rejected the request.", example = "Anand Rajashekar")
    private String approvedBy;

    @Schema(description = "Id of the employee who approved or rejected the request, if known.", example = "5")
    private Long approvedById;

    @Schema(description = "Timestamp the request was approved or rejected.", example = "2026-01-16T11:00:00")
    private LocalDateTime approvedAt;

    @Schema(description = "Current status of the request.", example = "PENDING")
    private RegularizationStatus status;

    @Schema(description = "Reason given when the request is rejected.", example = "No corroborating movement record for the claimed hours")
    private String rejectionReason;

    @Schema(description = "Clock event types missing from the original attendance record.", example = "[\"EVENING_CLOCK_OUT\"]")
    private List<String> missingEvents;
}
