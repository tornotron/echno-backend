package org.tornotron.echno_backend.wbs.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class WbsBulkCreateDto {

    @NotNull(message = "elements is required(type: List<WbsElementCreationDto>)")
    @Valid
    private List<WbsElementCreationDto> elements;
}
