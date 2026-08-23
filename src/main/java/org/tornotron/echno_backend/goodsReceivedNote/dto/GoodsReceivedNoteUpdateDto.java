package org.tornotron.echno_backend.goodsReceivedNote.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "Payload to update the editable header fields of an existing goods received note. "
        + "Line items are not changed through this payload.")
public class GoodsReceivedNoteUpdateDto {

    @Schema(description = "Id of the GRN to update.", example = "231")
    @NotNull(message = "GRN ID is required")
    private Long id;

    @Schema(description = "Revised receipt date.", example = "2026-08-01T10:30:00")
    private LocalDateTime receivedOn;

    @Schema(description = "Revised receiving employee.", example = "15")
    private Long receivedByEmployeeId;

    @Schema(description = "Revised delivery challan number.", example = "DC-88213")
    private String deliveryChallanNumber;

    @Schema(description = "Revised vendor invoice number.", example = "INV-5567")
    private String invoiceNumber;

    @Schema(description = "Revised vendor invoice amount.", example = "41475.00")
    private Double invoiceAmount;

    @Schema(description = "Revised storage location.", example = "7")
    private Long storageLocationId;
}
