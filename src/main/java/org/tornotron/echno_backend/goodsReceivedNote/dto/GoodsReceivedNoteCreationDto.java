package org.tornotron.echno_backend.goodsReceivedNote.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "Payload to record a goods received note. The received quantities on the items are "
        + "posted into stock at the given storage location and added to the matching lines of the "
        + "purchase order, whose status then follows from the quantities received against it. The GRN "
        + "number is allocated by the server and returned on the created note; it is not part of this "
        + "payload.")
public class GoodsReceivedNoteCreationDto {

    @Schema(description = "When the goods were received.", example = "2026-08-01T10:30:00")
    @NotNull(message = "received on date is required")
    private LocalDateTime receivedOn;

    @Schema(description = "Employee who received the goods.", example = "15")
    @NotNull(message = "received by username is required")
    private Long receivedByEmployeeId;

    @Schema(description = "Vendor the goods were received from.", example = "17")
    @NotNull(message = "vendor ID is required")
    private Long vendorId;

    @Schema(description = "Purchase order the receipt is matched against, if any.", example = "108")
    private Long purchaseOrderId;

    @Schema(description = "Vendor delivery challan number.", example = "DC-88213")
    @Size(max = 50, message = "delivery challan number must not exceed 50 characters")
    private String deliveryChallanNumber;

    @Schema(description = "Vendor invoice number for the receipt, if supplied.", example = "INV-5567")
    @Size(max = 50, message = "invoice number must not exceed 50 characters")
    private String invoiceNumber;

    @Schema(description = "Vendor invoice amount for the receipt, if supplied.", example = "41475.00")
    private Double invoiceAmount;

    @Schema(description = "Project the goods are received for.", example = "42")
    @NotNull(message = "project ID is required")
    private Long projectId;

    @Schema(description = "Storage location the received quantities are booked into.", example = "7")
    private Long storageLocationId;

    @Schema(description = "Received line items. At least one item is required.")
    @NotEmpty(message = "items list cannot be empty")
    @Valid
    private List<GrnItemDto> items;

    @Schema(description = "Set this when the delivery really did exceed the purchase order and the site "
            + "took it anyway. Without it, a line that would take a material past the quantity ordered "
            + "is refused, which is what catches a mistyped quantity. With it the excess is recorded "
            + "and the note is marked as an acknowledged over-receipt. It has no effect on a receipt "
            + "that stays within the order.", example = "false")
    private Boolean allowOverReceipt;
}
