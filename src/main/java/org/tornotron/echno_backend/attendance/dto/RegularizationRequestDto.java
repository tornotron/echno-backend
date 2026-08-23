package org.tornotron.echno_backend.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Schema(description = "Payload to submit a request to correct an attendance record missing one or more clock events.")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegularizationRequestDto {

    @Schema(description = "Id of the attendance record to correct.", example = "781")
    @NotNull
    private Long attendanceId;

    @Schema(description = "Reason the events are missing.", example = "Phone battery died before evening clock-out")
    @NotBlank
    private String reason;

    @Schema(description = "Clock event types missing from the original record.", example = "[\"EVENING_CLOCK_OUT\"]")
    @NotEmpty
    private List<String> missingEvents;

    @Schema(description = "Corrected clock events to add in place of the missing ones, if known.")
    private List<ClockEventCreationDto> correctedEvents;
}
