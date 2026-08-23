package org.tornotron.echno_backend.project.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Schema(description = "A single project's partial update within a batch: the project id and the map of "
        + "fields to change on it.")
@Data
public class ProjectPatchDto {
    @Schema(description = "Id of the project to update.", example = "42")
    private Long id;

    @Schema(description = "Fields to change, keyed by project field name.",
            example = "{\"status\": \"COMPLETED\", \"progress\": 1.0}")
    private Map<String,Object> updates;
}
