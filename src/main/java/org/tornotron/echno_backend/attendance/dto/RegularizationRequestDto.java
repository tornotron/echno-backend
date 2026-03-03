package org.tornotron.echno_backend.attendance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegularizationRequestDto {

    @NotNull
    private Long attendanceId;

    @NotBlank
    private String reason;

    @NotEmpty
    private List<String> missingEvents;

    private List<ClockEventCreationDto> correctedEvents;
}
