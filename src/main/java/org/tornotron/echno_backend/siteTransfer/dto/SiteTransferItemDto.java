package org.tornotron.echno_backend.siteTransfer.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SiteTransferItemDto {

    private Long id;

    @NotNull(message = "material ID is required")
    private Long materialId;

    private String materialName;

    @NotNull(message = "sent quantity is required")
    @Min(value = 1, message = "sent quantity must be at least 1")
    private Integer sentQuantity;

    private String remarks;
}
