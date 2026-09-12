package org.tornotron.echno_backend.project.spatial.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Bulk import of a site structure, one row per leaf.")
public record SpatialImportRequest(@NotEmpty @Size(max = 5000) List<@Valid SpatialImportRow> rows) {}
