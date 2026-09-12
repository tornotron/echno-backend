package org.tornotron.echno_backend.modules.bim.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Building > Floor > Zone > Element tree proposed from a version's IfcBuilding,
 * IfcBuildingStorey and IfcSpace containment, with the construction elements under each.
 * Nothing in it exists in the spatial hierarchy until confirmed; {@code matchedNodeId} says
 * which entries already have a node carrying that GlobalId.
 */
@Schema(description = "A proposed site structure from an IFC's spatial containment, pending confirmation.")
@JsonIgnoreProperties(ignoreUnknown = true)
public record BimHierarchyProposalDto(
        UUID versionId,
        LocalDateTime generatedAt,
        LocalDateTime confirmedAt,
        List<ProposedBuilding> buildings,
        @Schema(description = "buildings, floors, zones, elements proposed; matched; unplaced (no storey).")
        Map<String, Integer> counts,
        @Schema(description = "What the last confirmation did, or null.") BimHierarchyConfirmResult confirmation
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProposedBuilding(String globalId, String name, String code, UUID matchedNodeId,
                                   List<ProposedFloor> floors) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProposedFloor(String globalId, String name, String code, Integer levelIndex, Double elevation,
                                UUID matchedNodeId, List<ProposedZone> zones) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProposedZone(String globalId, String name, String code, boolean defaultZone, UUID matchedNodeId,
                               List<ProposedElement> elements) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProposedElement(String globalId, String ifcType, String name, String code, String elementType,
                                  UUID matchedNodeId) {}
}
