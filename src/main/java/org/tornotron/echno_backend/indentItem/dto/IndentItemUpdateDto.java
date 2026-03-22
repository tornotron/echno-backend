package org.tornotron.echno_backend.indentItem.dto;

import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class IndentItemUpdateDto {

    private Long materialId;

    private String additionalSpecifications;

    @Positive(message = "Requested quantity must be positive")
    private Integer requestedQuantity;

    private Integer orderedQuantity;

    private String remarks;
}
