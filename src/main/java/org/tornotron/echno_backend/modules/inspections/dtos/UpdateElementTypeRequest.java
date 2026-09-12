package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Fields of an element type that may change after creation. Omitted fields are left as they are; "
        + "the code never changes.")
public record UpdateElementTypeRequest(
        @Size(max = 200) String name,
        @Size(max = 50) String groupCode,
        String description,
        Integer sortOrder,
        @Schema(description = "False retires the type from pickers without touching the elements that carry it.")
        Boolean active
) {}
