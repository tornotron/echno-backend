package org.tornotron.echno_backend.inventoryTransaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType.StockEffect;

import java.time.LocalDateTime;

/**
 * One entry in a material's movement history: a single stock movement condensed to the
 * fields a timeline needs, namely where the material was, when, in what direction and by
 * how much, the running balance around it and who booked it. Used by the Location module
 * timeline (issue #256) to answer "where has this material been, when, how much, and on
 * whose hand".
 */
@Data
@Schema(description = "A single movement of a material for its timeline history: the storage location it "
        + "applied to, when it happened, the movement type and its stock direction, the quantity changed, "
        + "the stock level either side of it, the source reference and the employee who booked it.")
public class MaterialMovementHistoryDto {

    @Schema(description = "Unique transaction id.", example = "5012")
    private Long id;

    @Schema(description = "When the movement was recorded.", example = "2026-08-01T10:30:00")
    private LocalDateTime transactionDate;

    @Schema(description = "Movement type, for example GRN, USE, TRANSFER_OUT, TRANSFER_IN, ADJUST or OPENING_BALANCE.",
            example = "USE")
    private InventoryTransactionType transactionType;

    @Schema(description = "Direction the movement type moves stock in: INCREASE, DECREASE, or EITHER when the "
            + "sign comes from the signed quantity.", example = "DECREASE")
    private StockEffect direction;

    @Schema(description = "Storage location the movement applied to.", example = "7")
    private Long storageLocationId;

    @Schema(description = "Storage location name.", example = "Site A main store")
    private String storageLocationName;

    @Schema(description = "Project the movement is booked against.", example = "42")
    private Long projectId;

    @Schema(description = "Project name.", example = "Tower B fit-out")
    private String projectName;

    @Schema(description = "Stock level at the storage location immediately before this movement.",
            example = "115.0")
    private Double openingStock;

    @Schema(description = "Signed change applied by this movement. Positive for stock in, negative for stock out.",
            example = "-15.0")
    private Double quantityChanged;

    @Schema(description = "Stock level at the storage location immediately after this movement.",
            example = "100.0")
    private Double closingStock;

    @Schema(description = "Display name of the employee who booked the movement. For automated movements this "
            + "is the actor who triggered the source event. Null when the ledger row records no creator.",
            example = "Asha Menon")
    private String createdByName;

    @Schema(description = "Source document reference for the movement, for example a GRN or challan number.",
            example = "GRN-2026-0042")
    private String referenceNumber;
}
