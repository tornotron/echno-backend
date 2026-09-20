package org.tornotron.echno_backend.holiday.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Schema(description = "Payload to declare a holiday, or to replace one in full.")
@Data
public class HolidayCreationDto {

    @Schema(description = "The date of the holiday. One holiday per date per organization.", example = "2026-10-02")
    @NotNull(message = "holidayDate is required")
    private LocalDate holidayDate;

    @Schema(description = "Name of the holiday.", example = "Gandhi Jayanti")
    @NotBlank(message = "name is required")
    @Size(max = 150, message = "name must not exceed 150 characters")
    private String name;

    @Schema(description = "A note about the holiday.", example = "National holiday")
    @Size(max = 500, message = "description must not exceed 500 characters")
    private String description;
}
