package org.tornotron.echno_backend.wbs.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Schema(description = "Payload to create a batch of WBS elements for a project in one call, for example "
        + "seeding \"Foundation Works\", \"RCC Column Casting - Block A\" and \"Electrical First Fix\" "
        + "together. Each entry is validated and created the same way as the single-element create "
        + "endpoint.")
@Data
public class WbsBulkCreateDto {

    @Schema(description = "WBS elements to create, in the order they should be created.")
    @NotNull(message = "elements is required(type: List<WbsElementCreationDto>)")
    @Valid
    private List<WbsElementCreationDto> elements;
}
