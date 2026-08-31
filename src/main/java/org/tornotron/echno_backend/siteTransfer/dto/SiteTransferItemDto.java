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

    @Schema(description = "Quantity recorded as having arrived at the receiving site, in the "
            + "material's unit. Null while the transfer is in transit and nobody has confirmed "
            + "anything about this line yet, which is not the same as saying nothing arrived. A "
            + "transfer between two stores on one project is received in full at creation, since "
            + "the material never leaves that site's custody.",
            example = "18", nullable = true, accessMode = Schema.AccessMode.READ_ONLY)
    private Integer receivedQuantity;

    @Schema(description = "How much of this line is neither at the sending site nor recorded as "
            + "having reached the receiving one: sent minus received, or the whole sent quantity "
            + "while nothing has been received. On a transfer still in transit this is stock on a "
            + "lorry. On one that has been received it is an open variance, closed by a stock "
            + "adjustment naming this transfer rather than by the transfer writing a loss of its "
            + "own. Zero once everything sent has arrived.",
            example = "0", accessMode = Schema.AccessMode.READ_ONLY)
    private Integer inTransitQuantity;

    @Schema(description = "Optional remarks for this line item.", example = "For column casting, Block C")
    private String remarks;
}
