package org.tornotron.echno_backend.siteTransfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "A single material line on a site transfer, naming the material and the quantity sent.")
@Data
public class SiteTransferItemDto {

    @Schema(description = "Id of the transfer item.", example = "84")
    private Long id;

    @Schema(description = "Id of the material being transferred.", example = "21")
    @NotNull(message = "material ID is required")
    private Long materialId;

    @Schema(description = "Name of the material being transferred.", example = "TMT Bar 12mm")
    private String materialName;

    @Schema(description = "Quantity sent, in the material's unit.", example = "500")
    @NotNull(message = "sent quantity is required")
    @Min(value = 1, message = "sent quantity must be at least 1")
    private Integer sentQuantity;

    @Schema(description = "Optional remarks for this line item.", example = "For column casting, Block C")
    private String remarks;
}
