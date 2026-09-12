package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "Payload to schedule a reinspection of an NCR or a defect.")
public record ScheduleReinspectionRequest(
        @Schema(description = "Employee id of the inspector who carries out the re-check.", example = "8")
        Long assignedInspectorId,
        @Schema(description = "Date the re-check is due; becomes the new inspection's scheduled date.",
                example = "2026-09-20")
        LocalDate targetDate,
        @Schema(description = "Copy every check point of the original inspection rather than only "
                + "the failed ones. Defaults to false.", example = "false")
        Boolean copyAllItems
) {
    public boolean copyAll() {
        return Boolean.TRUE.equals(copyAllItems);
    }
}
