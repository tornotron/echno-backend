package org.tornotron.echno_backend.goodsReceivedNote.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class GoodsReceivedNoteUpdateDto {

    @NotNull(message = "GRN ID is required")
    private Long id;

    private LocalDateTime receivedOn;

    private Long receivedByEmployeeId;

    private String deliveryChallanNumber;

    private String invoiceNumber;

    private Double invoiceAmount;

    private Long storageLocationId;
}
