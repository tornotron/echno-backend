package org.tornotron.echno_backend.inspection.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.inspection.DefectSeverity;
import org.tornotron.echno_backend.inspection.DefectStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * Defect payload shared by the create and update requests. The number of defects
 * recorded feeds the inspection's {@code defectsFound} count, computed server-side.
 */
@Schema(description = "A defect identified during an inspection, with its corrective action and resolution tracking.")
public record InspectionDefectRequest(
        @Schema(description = "Grouping the defect belongs to.", example = "Structural")
        @Size(max = 200) String category,
        @Schema(description = "Description of the defect.", example = "Honeycombing observed on column C4 at ground level")
        @NotBlank @Size(max = 1000) String description,
        @Schema(description = "Severity of the defect.", example = "major")
        DefectSeverity severity,
        @Schema(description = "Location of the defect within the inspected area.", example = "Column C4, ground floor")
        @Size(max = 300) String location,
        @Schema(description = "URLs or references of photos attached to this defect.")
        List<@Size(max = 500) String> photos,
        @Schema(description = "Action required to correct the defect.", example = "Chip out and re-pour affected section")
        @NotBlank @Size(max = 1000) String correctiveAction,
        @Schema(description = "Party responsible for carrying out the corrective action.", example = "ABC Contractors")
        @Size(max = 200) String responsibleParty,
        @Schema(description = "Target date for resolving the defect.", example = "2026-09-01")
        LocalDate targetDate,
        @Schema(description = "Current resolution status of the defect. Defaults to open when omitted.", example = "open")
        DefectStatus status,
        @Schema(description = "Date the defect was actually resolved, if closed.", example = "null")
        LocalDate resolvedDate
) {}
