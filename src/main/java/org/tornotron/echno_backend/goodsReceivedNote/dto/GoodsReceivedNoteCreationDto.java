package org.tornotron.echno_backend.goodsReceivedNote.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class GoodsReceivedNoteCreationDto {

    @NotBlank(message = "GRN number is required")
    @Size(min = 1, max = 50, message = "GRN number must be between 1 and 50 characters")
    private String grnNumber;

    @NotNull(message = "received on date is required")
    private LocalDateTime receivedOn;

    @NotNull(message = "received by username is required")
    private Long receivedByEmployeeId;

    @NotNull(message = "vendor ID is required")
    private Long vendorId;

    private Long purchaseOrderId;

    @Size(max = 50, message = "delivery challan number must not exceed 50 characters")
    private String deliveryChallanNumber;

    @Size(max = 50, message = "invoice number must not exceed 50 characters")
    private String invoiceNumber;

    private Double invoiceAmount;

    @NotEmpty(message = "items list cannot be empty")
    @Valid
    private List<GrnItemDto> items;
}
