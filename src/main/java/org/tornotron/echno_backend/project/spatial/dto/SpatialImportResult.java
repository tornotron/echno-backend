package org.tornotron.echno_backend.project.spatial.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "How many nodes the import created and how many already existed.")
public record SpatialImportResult(int created, int skipped) {}
