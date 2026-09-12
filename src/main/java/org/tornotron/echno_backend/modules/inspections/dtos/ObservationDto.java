package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.modules.inspections.DefectSeverity;
import org.tornotron.echno_backend.modules.inspections.ObservationOutcomeKind;
import org.tornotron.echno_backend.modules.inspections.ObservationReviewStatus;
import org.tornotron.echno_backend.modules.inspections.ObservationSource;
import org.tornotron.echno_backend.project.spatial.dto.SpatialPathSegment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Schema(description = "A finding on site as recorded by a person, a model or a device, with its "
        + "review decision and the record it produced.")
public record ObservationDto(
        UUID id,
        Long projectId,
        @Schema(description = "Inspection the observation belongs to. Null for a finding recorded "
                + "before any inspection existed for it.", nullable = true)
        UUID inspectionId,
        @Schema(description = "Site structure node, or null where only the free-text location was given.",
                nullable = true)
        UUID spatialNodeId,
        @Schema(description = "Ordered ancestors down to spatialNodeId. Empty when spatialNodeId is null.")
        List<SpatialPathSegment> spatialPath,
        @Schema(nullable = true) String locationNote,
        ObservationSource source,
        @Schema(nullable = true) String sourceDeviceId,
        @Schema(nullable = true) String missionRef,
        @Schema(nullable = true) String captureRef,
        @Schema(description = "The producer's own id for the finding; unique per organisation.", nullable = true)
        String externalRef,
        @Schema(nullable = true) String modelName,
        @Schema(nullable = true) String modelVersion,
        @Schema(description = "Model confidence in [0, 1]. Null for a human observation or a model that gives none.",
                nullable = true)
        BigDecimal confidence,
        LocalDateTime observedAt,
        @Schema(description = "Employee who saw it, for a human observation.", nullable = true)
        Long reportedById,
        String title,
        @Schema(nullable = true) String description,
        @Schema(nullable = true) String category,
        @Schema(nullable = true) DefectSeverity suggestedSeverity,
        @Schema(description = "Pointers to evidence, in the producer's own shape.", nullable = true)
        List<Map<String, Object>> evidenceRefs,
        ObservationReviewStatus reviewStatus,
        @Schema(nullable = true) Long reviewedById,
        @Schema(nullable = true) LocalDateTime reviewedAt,
        @Schema(nullable = true) String reviewNote,
        @Schema(description = "One entry per field the reviewer changed: field, before, after. "
                + "Set only on a MODIFIED observation.", nullable = true)
        List<Map<String, Object>> reviewChanges,
        ObservationOutcomeKind outcomeKind,
        @Schema(description = "Id of the check item, defect, NCR or inspection the observation "
                + "produced, according to outcomeKind. Null for NONE.", nullable = true)
        UUID outcomeRef,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public ObservationDto withSpatialPath(List<SpatialPathSegment> path) {
        return new ObservationDto(id, projectId, inspectionId, spatialNodeId, path, locationNote, source,
                sourceDeviceId, missionRef, captureRef, externalRef, modelName, modelVersion, confidence,
                observedAt, reportedById, title, description, category, suggestedSeverity, evidenceRefs,
                reviewStatus, reviewedById, reviewedAt, reviewNote, reviewChanges, outcomeKind, outcomeRef,
                createdAt, updatedAt);
    }
}
