package org.tornotron.echno_backend.payable.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PayableCreationDto {

    @NotBlank(message = "payable number is required")
    @Size(min = 1, max = 50, message = "payable number must be between 1 and 50 characters")
    private String payableNumber;

    @NotBlank(message = "contractor name is required")
    @Size(min = 1, max = 100, message = "contractor name must be between 1 and 100 characters")
    private String contractorName;

    @NotBlank(message = "contract type is required")
    private String contractType;

    @NotNull(message = "amount recorded is required")
    private BigDecimal amountRecorded;

    private BigDecimal amountPaid;

    private Long vendorId;

    private Long goodsReceivedNoteId;

    @NotNull(message = "project ID is required")
    private Long projectId;

    @NotNull(message = "created by employee id is required")
    private Long createdBy;
}
