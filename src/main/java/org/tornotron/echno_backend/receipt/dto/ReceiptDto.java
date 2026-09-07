package org.tornotron.echno_backend.receipt.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A receipt as it is served. A field without {@code nullable = true} is one the schema, the
 * mapper or the service behind it establishes as always present; see
 * {@code ReviewedResponseSchemas} for which is which.
 *
 * <p>Almost every column on this table is nullable, and the update path assigns every editable
 * field unconditionally, so a request that omits a field clears it rather than leaving it alone.
 * Both facts are why so much of this schema admits null.
 */
@Schema(description = "A receipt with its amount, payer details, tax breakdown and optional links to project and finance rows.")
@Data
public class ReceiptDto {

    @Schema(description = "Receipt id.", example = "42")
    private Long id;

    @Schema(description = "Generated receipt number.", example = "RCP-2027-000001")
    private String receiptNumber;

    @Schema(description = "Kind of receipt, as free text the client interprets. Null where none "
            + "was sent, including after an update that omitted the field, which clears it.",
            example = "payment", nullable = true)
    private String type;

    @Schema(description = "Lifecycle status, as free text the client interprets. Null where none "
            + "was sent, including after an update that omitted the field, which clears it.",
            example = "issued", nullable = true)
    private String status;

    @Schema(description = "Amount received in the given currency. Required by the create and "
            + "update endpoints, so a null belongs to a row written outside them.",
            example = "45000.00", nullable = true)
    private BigDecimal amount;

    @Schema(description = "Currency code for the amount. New receipts default to INR when the "
            + "request omits one, so null appears only on rows stored without a currency.",
            example = "INR", nullable = true)
    private String currency;

    @Schema(description = "Date the amount was received. Null where none was given, including "
            + "after an update that omitted the field.", example = "2026-08-20", nullable = true)
    private LocalDate receiptDate;

    @Schema(description = "How the amount was received. Null where none was recorded.",
            example = "Bank Transfer", nullable = true)
    private String paymentMethod;

    @Schema(description = "External transaction reference from the bank or gateway. Null for "
            + "money taken outside any such system, such as cash at the site office.",
            example = "TXN-8842190", nullable = true)
    private String transactionId;

    @Schema(description = "Cheque or internal reference number. Null where none was recorded.",
            example = "CHQ-000231", nullable = true)
    private String referenceNumber;

    @Schema(description = "Name of the person or company the amount was received from. Required "
            + "by the create and update endpoints, so a null belongs to a row written outside "
            + "them.", example = "Asset Homes Pvt Ltd", nullable = true)
    private String receivedFrom;

    @Schema(description = "Address of the payer. Null where none was recorded.",
            example = "12 MG Road, Kochi", nullable = true)
    private String receivedFromAddress;

    @Schema(description = "Tax amount included in the receipt. Null where the receipt carries no "
            + "tax breakdown, which leaves the tax unknown rather than zero.",
            example = "8100.00", nullable = true)
    private BigDecimal taxAmount;

    @Schema(description = "Tax rate applied, as a percentage. Null where the receipt carries no "
            + "tax breakdown.", example = "18.00", nullable = true)
    private BigDecimal taxRate;

    @Schema(description = "Type of tax applied. Null where the receipt carries no tax breakdown.",
            example = "GST", nullable = true)
    private String taxType;

    @Schema(description = "What the amount was received for. Null where nothing was written.",
            example = "Advance against Block C interior work", nullable = true)
    private String description;

    @Schema(description = "Free-text notes. Null where nothing was written.",
            example = "Received at site office against acknowledgement 118", nullable = true)
    private String notes;

    @Schema(description = "Id of the employee who issued the receipt. Null where it was not "
            + "captured. The column carries no foreign key, so a value may name an employee that "
            + "no longer exists.", example = "9", nullable = true)
    private Long issuedBy;

    @Schema(description = "Id of the project this receipt belongs to. Null for money received "
            + "against no project. The column carries no foreign key, so a value may name a "
            + "project that no longer exists.", example = "3", nullable = true)
    private Long projectId;

    @Schema(description = "Id of the payment this receipt records. Null where the receipt stands "
            + "alone. The column carries no foreign key, so a value may name a payment that no "
            + "longer exists.", example = "51", nullable = true)
    private Long paymentId;

    @Schema(description = "Id of the invoice this receipt settles. Null where the money was "
            + "received against no invoice. The column carries no foreign key, so a value may "
            + "name an invoice that no longer exists.", example = "44", nullable = true)
    private Long invoiceId;

    @Schema(description = "Id of the customer the amount was received from. Null where the payer "
            + "was recorded by name only. The column carries no foreign key, so a value may name "
            + "a customer that no longer exists.", example = "12", nullable = true)
    private Long customerId;

    @Schema(description = "Id of the owning organization. Set on every receipt the application "
            + "creates, so a null belongs to a row written outside it.", example = "1",
            nullable = true)
    private Long organizationId;

    @Schema(description = "Timestamp the receipt was created.", example = "2026-08-20T09:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "Timestamp the receipt was last updated.",
            example = "2026-08-22T14:20:00")
    private LocalDateTime updatedAt;
}
