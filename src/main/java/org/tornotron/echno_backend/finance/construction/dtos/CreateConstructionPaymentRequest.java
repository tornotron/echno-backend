package org.tornotron.echno_backend.finance.construction.dtos;

import jakarta.validation.constraints.*;
import org.tornotron.echno_backend.finance.construction.ConstructionPayeeType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentMethod;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Creates a construction payment voucher. The status is not accepted here: a new
 * voucher always starts PENDING (no ledger posting in this increment). The payment
 * number is generated server-side with the CPMT sequence.
 */
public record CreateConstructionPaymentRequest(
        @NotNull ConstructionPaymentType type,
        @NotNull ConstructionPaymentMethod method,
        ConstructionPayeeType payeeType,
        @NotNull Long projectId,
        UUID invoiceId,
        Long purchaseOrderId,
        Long vendorId,
        Long employeeId,
        Long subContractId,
        Long labourId,
        @Size(max = 200) String payeeName,
        @Size(max = 500) String payeeDetails,
        @NotNull @Positive BigDecimal amount,
        @Size(max = 10) String currency,
        @NotNull LocalDate paymentDate,
        @Size(max = 100) String transactionId,
        @Size(max = 100) String referenceNumber,
        @Size(max = 100) String bankName,
        @Size(max = 50) String accountNumber,
        @Size(max = 20) String ifscCode,
        Long verifiedBy,
        Instant verifiedAt,
        @Size(max = 1000) String description,
        @Size(max = 1000) String notes
) {}
