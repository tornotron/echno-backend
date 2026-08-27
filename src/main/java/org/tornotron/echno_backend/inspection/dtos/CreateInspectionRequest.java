package org.tornotron.echno_backend.inspection.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.inspection.InspectionCategory;
import org.tornotron.echno_backend.inspection.InspectionTrade;
import org.tornotron.echno_backend.inspection.InspectionType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Creates a new inspection. Status is forced to {@code SCHEDULED} and the result
 * stays unset until the inspection is concluded through an update; neither is
 * accepted here. The category falls back to the one derived from the type when it
 * is omitted. The summary counts are computed from the supplied check items and
 * defects.
 */
@Schema(description = "Payload to schedule a new inspection, with its checklist items and any defects already known at scheduling time.")
public record CreateInspectionRequest(
        @Schema(description = "Short title of the inspection.", example = "Foundation Pour - Block C")
        @NotBlank @Size(max = 200) String title,
        @Schema(description = "Category of inspection.", example = "SAFETY")
        @NotNull InspectionType type,
        @Schema(description = "Top-level grouping the inspection belongs to. Derived from the type when omitted.", example = "qa-qc")
        InspectionCategory category,
        @Schema(description = "QA/QC stage or trade the inspection covers. Left unset for safety and compliance inspections.", example = "reinforcement")
        InspectionTrade trade,
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
        @Schema(description = "Actual start time of the inspection, once carried out.", example = "2026-08-25T10:05:00")
        LocalDateTime actualStartTime,
        @Schema(description = "Actual end time of the inspection, once carried out.", example = "2026-08-25T11:20:00")
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
        @Schema(description = "Checklist items covered by the inspection.")
        @Valid List<InspectionCheckItemRequest> checkItems,
        @Schema(description = "Defects identified during the inspection.")
        @Valid List<InspectionDefectRequest> defects
) {}
