package org.tornotron.echno_backend.goodsReceivedNote.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A goods received note as it is served. A field without {@code nullable = true} is one the
 * schema, the mapper or the entity behind it establishes as always present; see
 * {@code ReviewedResponseSchemas} for which is which.
 */
@Data
@Schema(description = "A goods received note with its vendor, project, storage location and received line items.")
public class GoodsReceivedNoteDto {

    @Schema(description = "Unique GRN id.", example = "231")
    private Long id;

    @Schema(description = "Goods received note number.", example = "GRN-2026-0042")
    private String grnNumber;

    @Schema(description = "When the goods were received.", example = "2026-08-01T10:30:00")
    private LocalDateTime receivedOn;

    @Schema(description = "Employee who received the goods. Null where the receipt was not "
            + "attributed to one.", nullable = true)
    private EmployeeDto receivedBy;

    @Schema(description = "Vendor the goods were received from. Null on a receipt booked without "
            + "one, for example an internal transfer.", example = "17", nullable = true)
    private Long vendorId;

    @Schema(description = "Vendor name. Null whenever vendorId is.",
            example = "Ambuja Cements Ltd", nullable = true)
    private String vendorName;

    @Schema(description = "Purchase order the receipt is matched against. Null on a receipt "
            + "booked without one.", example = "108", nullable = true)
    private Long purchaseOrderId;

    @Schema(description = "Purchase order number. Null whenever purchaseOrderId is.",
            example = "PO-2026-0311", nullable = true)
    private String purchaseOrderNumber;

    @Schema(description = "Vendor delivery challan number. Null where none was recorded.",
            example = "DC-88213", nullable = true)
    private String deliveryChallanNumber;

    @Schema(description = "Vendor invoice number for the receipt. Null where none was supplied.",
            example = "INV-5567", nullable = true)
    private String invoiceNumber;

    @Schema(description = "Vendor invoice amount for the receipt. Null where no invoice was "
            + "supplied, which is not the same as an invoice for zero.", example = "41475.00",
            nullable = true)
    private Double invoiceAmount;

    @Schema(description = "Project the goods were received for. The column permits null and rows "
            + "exist without it, although the entity declares the association non-optional.",
            example = "42", nullable = true)
    private Long projectId;

    @Schema(description = "Project name. Null whenever projectId is, and also on a project that "
            + "has no name recorded.", example = "Tower B fit-out", nullable = true)
    private String projectName;

    @Schema(description = "Storage location the received quantities were booked into. Null where "
            + "the receipt was not booked into one.", example = "7", nullable = true)
    private Long storageLocationId;

    @Schema(description = "Storage location name. Null whenever storageLocationId is.",
            example = "Site A main store", nullable = true)
    private String storageLocationName;

    @Schema(description = "Received line items. Null rather than an empty array when the receipt "
            + "has no lines: the mapper clears an empty list deliberately.", nullable = true)
    private List<GrnItemDto> items;

    @Schema(description = "True when this receipt took a material past the quantity its purchase order "
            + "asked for and the excess was acknowledged on the payload.", example = "false")
    private boolean overReceiptAcknowledged;
}
