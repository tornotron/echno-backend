package org.tornotron.echno_backend.purchaseOrder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "Payload to update a purchase order's status, delivery date, remarks or total.")
@Data
public class PurchaseOrderUpdateDto {

    @Schema(description = "Id of the purchase order to update.", example = "204")
    @NotNull(message = "Purchase Order ID is required")
    private Long id;

    @Schema(description = "New lifecycle status.", example = "APPROVED")
    private String status;

    @Schema(description = "Updated expected delivery date.", example = "2026-02-14T00:00:00")
    private LocalDateTime expectedDeliveryDate;

    @Schema(description = "Updated remarks.", example = "Vendor confirmed dispatch for 2026-02-12")
    private String remarks;

    @Schema(description = "Updated total value in INR.", example = "492500.00")
    private BigDecimal totalAmount;
}
