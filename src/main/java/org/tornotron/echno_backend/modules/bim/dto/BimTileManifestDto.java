package org.tornotron.echno_backend.modules.bim.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "Short-lived presigned GET URLs for a version's glTF tiles, one per storey plus the coarse whole model.")
public record BimTileManifestDto(
        UUID modelId,
        UUID versionId,
        long expiresInSeconds,
        @Schema(description = "Decimated whole model for the first paint.") String coarseUrl,
        @Schema(description = "Products with no storey, or null.") String unassignedUrl,
        List<StoreyTile> storeys
) {
    public record StoreyTile(String globalId, String name, Double elevation, Integer elementCount, String url) {}
}
