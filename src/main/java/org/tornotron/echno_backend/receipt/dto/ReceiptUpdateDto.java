package org.tornotron.echno_backend.receipt.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "Payload to update a receipt. The receipt number is immutable and not accepted here.")
@Data
public class ReceiptUpdateDto {

    @Schema(description = "Kind of receipt.", example = "payment")
    private String type;

    @Schema(description = "Lifecycle status of the receipt.", example = "issued")
    private String status;

    @Schema(description = "Amount received in the given currency.", example = "45000.00")
    @NotNull
    private BigDecimal amount;

    @Schema(description = "Currency code for the amount.", example = "INR")
    private String currency;

    @Schema(description = "Date the amount was received.", example = "2026-08-20")
    private LocalDate receiptDate;

    @Schema(description = "How the amount was received.", example = "Bank Transfer")
    private String paymentMethod;

    @Schema(description = "External transaction reference from the bank or gateway.", example = "TXN-8842190")
    private String transactionId;

    @Schema(description = "Cheque or internal reference number.", example = "CHQ-000231")
    private String referenceNumber;

    @Schema(description = "Name of the person or company the amount was received from.", example = "Asset Homes Pvt Ltd")
    @NotBlank
    private String receivedFrom;

    @Schema(description = "Address of the payer.", example = "12 MG Road, Kochi")
    private String receivedFromAddress;

    @Schema(description = "Tax amount included in the receipt.", example = "8100.00")
    private BigDecimal taxAmount;

    @Schema(description = "Tax rate applied, as a percentage.", example = "18.00")
    private BigDecimal taxRate;

    @Schema(description = "Type of tax applied.", example = "GST")
    private String taxType;

    @Schema(description = "What the amount was received for.", example = "Advance against Block C interior work")
    private String description;

    @Schema(description = "Free-text notes.", example = "Received at site office against acknowledgement 118")
    private String notes;

    @Schema(description = "Id of the employee who issued the receipt.", example = "9")
    private Long issuedBy;

    @Schema(description = "Id of the project this receipt belongs to.", example = "3")
    private Long projectId;

    @Schema(description = "Id of the payment this receipt records.", example = "51")
    private Long paymentId;

    @Schema(description = "Id of the invoice this receipt settles.", example = "44")
    private Long invoiceId;

    @Schema(description = "Id of the customer the amount was received from.", example = "12")
    private Long customerId;
}
