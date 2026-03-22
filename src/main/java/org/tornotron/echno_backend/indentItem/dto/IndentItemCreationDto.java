package org.tornotron.echno_backend.indentItem.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class IndentItemCreationDto {

    private Long indentId;

    @NotNull(message = "Material ID is required")
    private Long materialId;

    private String additionalSpecifications;

    @NotNull(message = "Requested quantity is required")
    @Positive(message = "Requested quantity must be positive")
    private Integer requestedQuantity;

    private Integer orderedQuantity;

    private String remarks;
}
