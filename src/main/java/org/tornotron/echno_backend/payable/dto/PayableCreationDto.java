package org.tornotron.echno_backend.payable.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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
    @Positive(message = "amount recorded must be greater than zero")
    private BigDecimal amountRecorded;

    /**
     * The amount already settled when the payable is first recorded, for a debt that is
     * being carried in part-paid rather than raised from nothing. Optional, defaults to
     * zero, and may not exceed the recorded amount: every later payment goes through
     * {@code recordPayment}, which refuses to push the paid total past the recorded one,
     * so an opening figure above it would create a balance no payment could ever move.
     */
    @PositiveOrZero(message = "amount paid cannot be negative")
    private BigDecimal amountPaid;

    private Long vendorId;

    private Long goodsReceivedNoteId;

    @NotNull(message = "project ID is required")
    private Long projectId;

    @NotNull(message = "created by employee id is required")
    private Long createdBy;
}
