package org.tornotron.echno_backend.goodsReceivedNote.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.user.dto.UserDto;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "A goods received note with its vendor, project, storage location and received line items.")
public class GoodsReceivedNoteDto {

    @Schema(description = "Unique GRN id.", example = "231")
    private Long id;

    @Schema(description = "Goods received note number.", example = "GRN-2026-0042")
    private String grnNumber;

    @Schema(description = "When the goods were received.", example = "2026-08-01T10:30:00")
    private LocalDateTime receivedOn;

    @Schema(description = "Employee who received the goods.")
    private EmployeeDto receivedBy;

    @Schema(description = "Vendor the goods were received from.", example = "17")
    private Long vendorId;

    @Schema(description = "Vendor name.", example = "Ambuja Cements Ltd")
    private String vendorName;

    @Schema(description = "Purchase order the receipt is matched against, if any.", example = "108")
    private Long purchaseOrderId;

    @Schema(description = "Purchase order number, if matched.", example = "PO-2026-0311")
    private String purchaseOrderNumber;

    @Schema(description = "Vendor delivery challan number.", example = "DC-88213")
    private String deliveryChallanNumber;

    @Schema(description = "Vendor invoice number for the receipt, if supplied.", example = "INV-5567")
    private String invoiceNumber;

    @Schema(description = "Vendor invoice amount for the receipt, if supplied.", example = "41475.00")
    private Double invoiceAmount;

    @Schema(description = "Project the goods were received for.", example = "42")
    private Long projectId;

    @Schema(description = "Project name.", example = "Tower B fit-out")
    private String projectName;

    @Schema(description = "Storage location the received quantities were booked into.", example = "7")
    private Long storageLocationId;

    @Schema(description = "Storage location name.", example = "Site A main store")
    private String storageLocationName;

    @Schema(description = "Received line items.")
    private List<GrnItemDto> items;
}
