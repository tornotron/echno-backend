package org.tornotron.echno_backend.inspection.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.inspection.InspectionResult;
import org.tornotron.echno_backend.inspection.InspectionStatus;
import org.tornotron.echno_backend.inspection.InspectionType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Full replacement of an inspection. Status is set directly and the result is
 * optional (set once the inspection is concluded). The check items and defects
 * are rebuilt from the payload and the summary counts recomputed from them.
 */
@Schema(description = "Payload to fully replace an existing inspection, including its status, result, checklist items and defects.")
public record UpdateInspectionRequest(
        @Schema(description = "Short title of the inspection.", example = "Foundation Pour - Block C")
        @NotBlank @Size(max = 200) String title,
        @Schema(description = "Category of inspection.", example = "SAFETY")
        @NotNull InspectionType type,
        @Schema(description = "Current lifecycle status of the inspection.", example = "COMPLETED")
        @NotNull InspectionStatus status,
        @Schema(description = "Overall result once the inspection is concluded.", example = "PASSED")
        InspectionResult result,
        @Schema(description = "Id of the project this inspection is against.", example = "14")
        Long projectId,
        @Schema(description = "Site or building location of the inspection.", example = "Block C, Ground Floor")
        @Size(max = 300) String location,
        @Schema(description = "Specific area inspected within the location.", example = "Column grid C3-C6")
        @Size(max = 300) String areaInspected,
        @Schema(description = "Reference to the drawing used during the inspection.", example = "DWG-STR-014 Rev C")
        @Size(max = 200) String drawingReference,
        @Schema(description = "Date the inspection is scheduled for.", example = "2026-08-25")
        @NotNull LocalDate scheduledDate,
        @Schema(description = "Scheduled time of day.", example = "10:00")
        @Size(max = 20) String scheduledTime,
        @Schema(description = "Actual start time of the inspection.", example = "2026-08-25T10:05:00")
        LocalDateTime actualStartTime,
        @Schema(description = "Actual end time of the inspection.", example = "2026-08-25T11:20:00")
        LocalDateTime actualEndTime,
        @Schema(description = "Duration of the inspection in minutes.", example = "75")
        @PositiveOrZero Integer duration,
        @Schema(description = "Id of the employee performing the inspection.", example = "8")
        @NotNull Long inspectorId,
        @Schema(description = "Id of the contractor whose work is being inspected, if applicable.", example = "3")
        Long contractorId,
        @Schema(description = "Name of the client's representative present at the inspection.", example = "K. Suresh")
        @Size(max = 200) String clientRepresentative,
        @Schema(description = "Names of other attendees present.", example = "[\"Ravi Kumar\", \"Priya Nair\"]")
        List<@Size(max = 200) String> attendees,
        @Schema(description = "Weather conditions at the time of inspection.", example = "Clear, dry")
        @Size(max = 200) String weatherConditions,
        @Schema(description = "Ambient temperature at the time of inspection.", example = "31C")
        @Size(max = 50) String temperature,
        @Schema(description = "Checklist items, replacing any previously recorded for this inspection.")
        @Valid List<InspectionCheckItemRequest> checkItems,
        @Schema(description = "Defects, replacing any previously recorded for this inspection.")
        @Valid List<InspectionDefectRequest> defects
) {}
