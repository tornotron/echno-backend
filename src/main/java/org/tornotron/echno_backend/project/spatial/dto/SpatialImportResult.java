package org.tornotron.echno_backend.project.spatial.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Counts are of distinct nodes, not of rows: four rows under one building count that building
 * once, as created or as matched. The import is all or nothing, so a failed import reports
 * nothing here; it fails with the row and the reason.
 */
@Schema(description = "How many distinct nodes the import created and how many already existed, "
        + "in total and per level.")
public record SpatialImportResult(
        @Schema(description = "Distinct nodes created, all levels.", example = "9") int created,
        @Schema(description = "Same value as matched; kept for the first callers of this endpoint.",
                example = "0", deprecated = true) int skipped,
        @Schema(description = "Distinct nodes that already existed on the code path and were reused.",
                example = "0") int matched,
        @Schema(description = "Rows the import read.", example = "4") int rows,
        SpatialImportLevelCounts buildings,
        SpatialImportLevelCounts floors,
        SpatialImportLevelCounts zones,
        SpatialImportLevelCounts elements
) {}
