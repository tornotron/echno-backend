package org.tornotron.echno_backend.purchaseOrder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "Payload to update a purchase order's status, project, delivery date or remarks.")
@Data
public class PurchaseOrderUpdateDto {

    @Schema(description = "Id of the purchase order to update.", example = "204")
    @NotNull(message = "Purchase Order ID is required")
    private Long id;

    @Schema(description = "New lifecycle status.", example = "APPROVED")
    private String status;

    @Schema(description = "Reallocate the order to a different project.", example = "17")
    private Long projectId;

    @Schema(description = "Updated expected delivery date.", example = "2026-02-14T00:00:00")
    private LocalDateTime expectedDeliveryDate;

    @Schema(description = "Updated remarks.", example = "Vendor confirmed dispatch for 2026-02-12")
    private String remarks;

    /**
     * Ignored. The order total is the sum of its line items and is recomputed whenever one of
     * them is added, changed or removed, so a value sent here would be overwritten by the next
     * line edit. Kept on the payload only until the web client stops sending it; see
     * tornotron/echno-core#57.
     */
    @Schema(description = "Ignored. The total is derived from the line items and recomputed on every line change.",
            example = "492500.00")
    private BigDecimal totalAmount;
}
