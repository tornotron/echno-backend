package org.tornotron.echno_backend.category.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Schema(description = "Payload naming a category and the fields to change on it, used for batch updates.")
@Data
public class CategoryPatchDto {
    @Schema(description = "Id of the category to update.", example = "7")
    private Long id;

    @Schema(description = "Map of field names to their new values, for example {\"name\": \"Cement & Binders\"}.", example = "{\"description\": \"OPC, PPC and other cementitious binders\"}")
    private Map<String, Object> updates;
}
