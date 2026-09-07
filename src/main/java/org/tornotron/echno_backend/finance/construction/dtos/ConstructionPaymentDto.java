package org.tornotron.echno_backend.finance.construction.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.finance.construction.ConstructionPayeeType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentMethod;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentVoucherStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "A construction payment voucher recording money paid out to a vendor, subcontractor, "
        + "labour or employee on a project, with its method, payee and verification details.")
public record ConstructionPaymentDto(
        @Schema(description = "Unique payment voucher id.", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
        UUID id,

        @Schema(description = "Human-readable voucher number assigned by the system.", example = "CPMT-2026-0031")
        String paymentNumber,

        @Schema(description = "Kind of payment.", example = "VENDOR_PAYMENT")
        ConstructionPaymentType type,

        @Schema(description = "Lifecycle status of the voucher.", example = "COMPLETED")
        ConstructionPaymentVoucherStatus status,

        @Schema(description = "Method used to settle the payment.", example = "BANK_TRANSFER")
        ConstructionPaymentMethod method,

        @Schema(description = "Category of party being paid. Null on a voucher that records no payee category; "
                + "the column is nullable and nothing writes a default.", example = "VENDOR", nullable = true)
        ConstructionPayeeType payeeType,

        @Schema(description = "Project the payment is charged to.", example = "42")
        Long projectId,

        @Schema(description = "Construction invoice this payment settles, if any. Null where none is matched.",
                example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301", nullable = true)
        UUID invoiceId,

        @Schema(description = "Purchase order the payment relates to, if any. Null where none is matched.",
                example = "108", nullable = true)
        Long purchaseOrderId,

        @Schema(description = "Vendor being paid, when the payee is a vendor. Null on any other payee type.",
                example = "17", nullable = true)
        Long vendorId,

        @Schema(description = "Employee being paid, when the payee is an employee. Null on any other payee "
                + "type.", example = "5", nullable = true)
        Long employeeId,

        @Schema(description = "Subcontract the payment relates to, when the payee is a subcontractor. Null on "
                + "any other payee type.", example = "23", nullable = true)
        Long subContractId,

        @Schema(description = "Labour record being paid, when the payee is labour. Null on any other payee "
                + "type.", example = "61", nullable = true)
        Long labourId,

        @Schema(description = "Name of the party being paid. Null where none was recorded.",
                example = "Sundar Building Materials", nullable = true)
        String payeeName,

        @Schema(description = "Free-text payee details. Null where none were recorded.",
                example = "Contact: Ravi, GST 29ABCDE1234F1Z5", nullable = true)
        String payeeDetails,

        @Schema(description = "Amount paid.", example = "40000.00")
        BigDecimal amount,

        @Schema(description = "Currency code.", example = "INR")
        String currency,

        @Schema(description = "Date the payment was made.", example = "2026-08-05")
        LocalDate paymentDate,

        @Schema(description = "Bank or gateway transaction id. Null on a payment settled without one, such as "
                + "one made in cash.", example = "TXN20260805123456", nullable = true)
        String transactionId,

        @Schema(description = "Cheque or reference number for the payment. Null on a payment settled without "
                + "one, such as one made in cash.", example = "CHQ-000123", nullable = true)
        String referenceNumber,

        @Schema(description = "Bank the payment was drawn on. Null on a payment that was not drawn on a bank.",
                example = "HDFC Bank", nullable = true)
        String bankName,

        @Schema(description = "Bank account number used for the payment. Null on a payment that was not drawn "
                + "on a bank.", example = "50100123456789", nullable = true)
        String accountNumber,

        @Schema(description = "IFSC code of the paying bank branch. Null on a payment that was not drawn on a "
                + "bank.", example = "HDFC0001234", nullable = true)
        String ifscCode,

        @Schema(description = "User id that raised the voucher, taken from the session that created "
                + "it. Null on vouchers raised before the voucher recorded this.", example = "8", nullable = true)
        Long raisedBy,

        @Schema(description = "Name of the user that raised the voucher, on the same fallbacks as "
                + "the verifier name. Null only where the voucher does not record who raised it.",
                example = "Hrishi", nullable = true)
        String raisedByName,

        @Schema(description = "User id that verified the voucher, taken from the session that verified it. Null "
                + "until the voucher is verified.", example = "2", nullable = true)
        Long verifiedBy,

        @Schema(description = "Name of the user that verified the voucher, or their email where the "
                + "account carries no name. Reads \"User #<id>\" when the account has since been "
                + "deleted; null only when the voucher was never verified.",
                example = "Aneesh Johny", nullable = true)
        String verifiedByName,

        @Schema(description = "Timestamp the voucher was verified. Null until the voucher is verified.",
                example = "2026-08-06T10:20:00Z", nullable = true)
        Instant verifiedAt,

        @Schema(description = "Why the voucher was voided, recorded by the cancel action. Null on a voucher "
                + "that has not been cancelled.",
                example = "Duplicate of CPMT-000118, raised twice for the same invoice", nullable = true)
        String cancellationReason,

        @Schema(description = "Description of what the payment covers. Null where none was recorded.",
                example = "Payment for cement supply, batch 2", nullable = true)
        String description,

        @Schema(description = "Internal notes. Null where none were recorded.",
                example = "Approved by site engineer", nullable = true)
        String notes
) {}
