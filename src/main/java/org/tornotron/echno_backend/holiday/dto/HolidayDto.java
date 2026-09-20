package org.tornotron.echno_backend.holiday.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "One declared holiday of the organization.")
@Data
public class HolidayDto {

    @Schema(description = "Id of the holiday.", example = "12")
    private Long id;

    @Schema(description = "Id of the organization the holiday belongs to.", example = "2")
    private Long organizationId;

    @Schema(description = "The date of the holiday.", example = "2026-10-02")
    private LocalDate holidayDate;

    @Schema(description = "Name of the holiday.", example = "Gandhi Jayanti")
    private String name;

    @Schema(nullable = true, description = "A note about the holiday. Null where none was written.",
            example = "National holiday")
    private String description;

    @Schema(description = "Time the holiday was declared.", example = "2026-01-05T10:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "Time the holiday was last changed.", example = "2026-01-05T10:00:00")
    private LocalDateTime updatedAt;
}
