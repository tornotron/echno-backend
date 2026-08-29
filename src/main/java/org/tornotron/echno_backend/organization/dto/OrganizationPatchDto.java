package org.tornotron.echno_backend.organization.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Schema(description = "A single organization's partial update within a batch: the organization id "
        + "and the fields to change on it.")
@Data
public class OrganizationPatchDto {

    @Schema(description = "Id of the organization to update.", example = "2")
    private Long id;

    @Schema(implementation = OrganizationUpdateFieldsDto.class)
    private Map<String, Object> updates;
}
