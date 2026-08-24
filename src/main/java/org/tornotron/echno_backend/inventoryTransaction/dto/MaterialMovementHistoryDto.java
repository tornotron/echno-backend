package org.tornotron.echno_backend.inventoryTransaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType.StockEffect;

import java.time.LocalDateTime;

/**
 * One entry in a material's movement history: a single stock movement condensed to the
 * fields a timeline needs, namely where the material was, when, in what direction and by
 * how much. Used by the Location module timeline (issue #256) to answer "where has this
 * material been, when, and how much".
 */
@Data
@Schema(description = "A single movement of a material for its timeline history: the storage location it "
        + "applied to, when it happened, the movement type and its stock direction, the quantity changed "
        + "and the source reference.")
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

    @Schema(description = "Signed change applied by this movement. Positive for stock in, negative for stock out.",
            example = "-15.0")
    private Double quantityChanged;

    @Schema(description = "Source document reference for the movement, for example a GRN or challan number.",
            example = "GRN-2026-0042")
    private String referenceNumber;
}
