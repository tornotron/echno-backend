package org.tornotron.echno_backend.goodsReceivedNote.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class GrnItemDto {

    private Long id;

    @NotNull(message = "material ID is required")
    private Long materialId;

    private String materialName;

    @NotNull(message = "ordered quantity is required")
    @Min(value = 0, message = "ordered quantity must be at least 0")
    private Integer orderedQuantity;

    @NotNull(message = "received quantity is required")
    @Min(value = 0, message = "received quantity must be at least 0")
    private Integer receivedQuantity;
}
