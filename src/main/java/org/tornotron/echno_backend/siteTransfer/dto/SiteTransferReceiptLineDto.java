package org.tornotron.echno_backend.siteTransfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "How much of one transfer line arrived. The line is named by its own id "
        + "rather than by its material, because a transfer may carry the same material on more "
        + "than one line and a receipt has to say which of them it answers.")
@Data
public class SiteTransferReceiptLineDto {

    @Schema(description = "Id of the site transfer line this quantity is against.", example = "84")
    @NotNull(message = "transfer item id is required")
    private Long itemId;

    @Schema(description = "Quantity that arrived, in the material's unit. Zero is a legitimate "
            + "answer and means this line's material did not arrive on this delivery; the "
            + "quantity stays in transit and the transfer is not moved on by it.",
            example = "18")
    @NotNull(message = "received quantity is required")
    @Min(value = 0, message = "received quantity cannot be negative")
    private Integer receivedQuantity;
}
