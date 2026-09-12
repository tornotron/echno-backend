package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Fields of a trade that may change after creation. Omitted fields are left as they are; "
        + "the code never changes.")
public record UpdateTradeRequest(
        @Size(max = 200) String name,
        @Size(max = 50) String groupCode,
        String description,
        Integer sortOrder,
        @Schema(description = "False retires the trade from pickers without touching the rows that reference it.")
        Boolean active
) {}
