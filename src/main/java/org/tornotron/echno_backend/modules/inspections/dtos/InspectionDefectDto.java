package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.modules.inspections.DefectSeverity;
import org.tornotron.echno_backend.modules.inspections.DefectStatus;

import java.time.LocalDate;
import java.util.List;
import org.tornotron.echno_backend.project.spatial.dto.SpatialPathSegment;
import java.util.UUID;

@Schema(description = "A defect identified during an inspection, as returned by the API.")
public record InspectionDefectDto(
        UUID id,
        @Schema(description = "Grouping the defect belongs to. Null where none was recorded.",
                nullable = true)
        String category,
        String description,
        @Schema(description = "How serious the defect is. Null where none was recorded: the "
                + "migration that promoted the free-text column to the enum cleared blank values "
                + "to null rather than guessing a severity.", nullable = true)
        DefectSeverity severity,
        @Schema(description = "Where on site the defect was found. Null where none was recorded.",
                nullable = true)
        String location,
        List<String> photos,
        String correctiveAction,
        @Schema(description = "Who is accountable for putting the defect right. Null where none "
                + "was recorded.", nullable = true)
        String responsibleParty,
        @Schema(description = "Date the corrective action is due. Null where none was agreed.",
                nullable = true)
        LocalDate targetDate,
        @Schema(description = "Where the defect stands. The service substitutes OPEN wherever a "
                + "payload omits it, but the column permits null, so a row loaded outside the "
                + "application can carry none.", nullable = true)
        DefectStatus status,
        @Schema(description = "Date the defect was resolved. Null until it is.", nullable = true)
        LocalDate resolvedDate,
        @Schema(description = "Site structure node the defect sits on, or null where only the free-text "
                + "location was recorded.", nullable = true)
        UUID spatialNodeId,
        @Schema(description = "Ordered ancestors from the building down to spatialNodeId, for a "
                + "breadcrumb with no second call. Empty when spatialNodeId is null.")
        List<SpatialPathSegment> spatialPath,
        @Schema(description = "Observation the defect was raised from. Null on defects recorded "
                + "before observations existed.", nullable = true)
        UUID observationId
) {

    /** The same record with the breadcrumb filled in; the mapper leaves it empty. */
    public InspectionDefectDto withSpatialPath(List<SpatialPathSegment> path) {
        return new InspectionDefectDto(
                id, category, description, severity, location, photos, correctiveAction,
                responsibleParty, targetDate, status, resolvedDate, spatialNodeId, path, observationId);
    }
}
