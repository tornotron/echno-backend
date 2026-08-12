package org.tornotron.echno_backend.finance.construction.dtos;

import org.tornotron.echno_backend.finance.construction.ConstructionPayeeType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentMethod;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentVoucherStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ConstructionPaymentDto(
        UUID id,
        String paymentNumber,
        ConstructionPaymentType type,
        ConstructionPaymentVoucherStatus status,
        ConstructionPaymentMethod method,
        ConstructionPayeeType payeeType,
        Long projectId,
        UUID invoiceId,
        Long purchaseOrderId,
        Long vendorId,
        Long employeeId,
        Long subContractId,
        Long labourId,
        String payeeName,
        String payeeDetails,
        BigDecimal amount,
        String currency,
        LocalDate paymentDate,
        String transactionId,
        String referenceNumber,
        String bankName,
        String accountNumber,
        String ifscCode,
        Long verifiedBy,
        Instant verifiedAt,
        String description,
        String notes
) {}
