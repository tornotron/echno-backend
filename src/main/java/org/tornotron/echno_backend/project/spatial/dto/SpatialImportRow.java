package org.tornotron.echno_backend.project.spatial.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "One spreadsheet row of the site structure: codes from the building down. "
        + "A code doubles as the name on first creation. Nodes that already exist on the code "
        + "path are skipped, so the same rows can be posted again.")
public record SpatialImportRow(
        @Schema(example = "B1") @NotBlank @Size(max = 50) String building,
        @Schema(example = "L03") @Size(max = 50) String floor,
        @Schema(description = "Applied when the floor is created.", example = "3") Integer levelIndex,
        @Schema(description = "Omitted with an element present: the floor's default zone is used.",
                example = "Z1") @Size(max = 50) String zone,
        @Schema(example = "C4") @Size(max = 50) String element,
        @Schema(description = "Applied when the element is created.", example = "column")
        @Size(max = 50) String elementType
) {}
