package org.tornotron.echno_backend.task.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;


@Schema(description = "A single task's partial update within a batch: the task id and the map of fields "
        + "to change on it.")
@Data
public class TaskPatchDto {
    @Schema(description = "Id of the task to update.", example = "108")
    private Long id;

    @Schema(description = "Fields to change, keyed by task field name.",
            example = "{\"status\": \"DONE\", \"progress\": 1.0}")
    private Map<String, Object> updates;
}
