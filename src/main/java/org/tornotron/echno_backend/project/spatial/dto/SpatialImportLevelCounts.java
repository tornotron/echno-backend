package org.tornotron.echno_backend.project.spatial.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Distinct nodes of one level touched by an import: created new, or matched to a "
        + "node that already existed on the code path. A node referenced by several rows counts once.")
public record SpatialImportLevelCounts(
        @Schema(description = "Nodes of this level created by the import.", example = "2") int created,
        @Schema(description = "Nodes of this level that already existed and were reused.", example = "1") int matched
) {
    public static final SpatialImportLevelCounts NONE = new SpatialImportLevelCounts(0, 0);
}
