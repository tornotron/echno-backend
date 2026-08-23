package org.tornotron.echno_backend.finance.construction.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import org.tornotron.echno_backend.finance.construction.ConstructionPayeeType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentMethod;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentVoucherStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Full replacement of an editable construction payment voucher. The status is set
 * directly (no ledger posting in this increment); no JournalEntry is created on any
 * status transition.
 */
@Schema(description = "Payload to fully replace an editable construction payment voucher. The status is "
        + "set directly, and no ledger journal entry is created on any status transition.")
public record UpdateConstructionPaymentRequest(
        @Schema(description = "Kind of payment.", example = "VENDOR_PAYMENT")
        @NotNull ConstructionPaymentType type,

        @Schema(description = "Lifecycle status to set on the voucher.", example = "VERIFIED")
        @NotNull ConstructionPaymentVoucherStatus status,

        @Schema(description = "Method used to settle the payment.", example = "BANK_TRANSFER")
        @NotNull ConstructionPaymentMethod method,

        @Schema(description = "Category of party being paid.", example = "VENDOR")
        ConstructionPayeeType payeeType,

        @Schema(description = "Project the payment is charged to.", example = "42")
        @NotNull Long projectId,

        @Schema(description = "Construction invoice this payment settles, if any.",
                example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
        UUID invoiceId,

        @Schema(description = "Purchase order the payment relates to, if any.", example = "108")
        Long purchaseOrderId,

        @Schema(description = "Vendor being paid, when the payee is a vendor.", example = "17")
        Long vendorId,

        @Schema(description = "Employee being paid, when the payee is an employee.", example = "5")
        Long employeeId,

        @Schema(description = "Subcontract the payment relates to, when the payee is a subcontractor.", example = "23")
        Long subContractId,

        @Schema(description = "Labour record being paid, when the payee is labour.", example = "61")
        Long labourId,

        @Schema(description = "Name of the party being paid.", example = "Sundar Building Materials")
        @Size(max = 200) String payeeName,

        @Schema(description = "Free-text payee details.", example = "Contact: Ravi, GST 29ABCDE1234F1Z5")
        @Size(max = 500) String payeeDetails,

        @Schema(description = "Amount to pay. Must be positive.", example = "40000.00")
        @NotNull @Positive BigDecimal amount,

        @Schema(description = "Currency code.", example = "INR")
        @Size(max = 10) String currency,

        @Schema(description = "Date the payment was made.", example = "2026-08-05")
        @NotNull LocalDate paymentDate,

        @Schema(description = "Bank or gateway transaction id.", example = "TXN20260805123456")
        @Size(max = 100) String transactionId,

        @Schema(description = "Cheque or reference number for the payment.", example = "CHQ-000123")
        @Size(max = 100) String referenceNumber,

        @Schema(description = "Bank the payment was drawn on.", example = "HDFC Bank")
        @Size(max = 100) String bankName,

        @Schema(description = "Bank account number used for the payment.", example = "50100123456789")
        @Size(max = 50) String accountNumber,

        @Schema(description = "IFSC code of the paying bank branch.", example = "HDFC0001234")
        @Size(max = 20) String ifscCode,

        @Schema(description = "User id that verified the voucher.", example = "2")
        Long verifiedBy,

        @Schema(description = "Timestamp the voucher was verified.", example = "2026-08-06T10:20:00Z")
        Instant verifiedAt,

        @Schema(description = "Description of what the payment covers.", example = "Payment for cement supply, batch 2")
        @Size(max = 1000) String description,

        @Schema(description = "Internal notes.", example = "Approved by site engineer")
        @Size(max = 1000) String notes
) {}
