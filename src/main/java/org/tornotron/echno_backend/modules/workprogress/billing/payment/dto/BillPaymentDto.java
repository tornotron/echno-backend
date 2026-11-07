package org.tornotron.echno_backend.modules.workprogress.billing.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.BillPaymentProvider;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.BillPaymentStatus;

@Schema(description = "One attempt to pay a bill.")
public record BillPaymentDto(
        UUID id,
        UUID billId,
        @Schema(description = "Rupees.")
        BigDecimal amount,
        @Schema(description = "What went onto the payable when it settled. Less than the amount when finance has a difference to reconcile.", nullable = true)
        BigDecimal appliedAmount,
        BillPaymentStatus status,
        BillPaymentProvider provider,
        @Schema(description = "True for the test provider: no money moved.")
        boolean test,
        @Schema(nullable = true)
        String bankReference,
        @Schema(nullable = true)
        LocalDate paidOn,
        @Schema(description = "Who it was paid to, for a provider payment.", nullable = true)
        String beneficiaryName,
        @Schema(nullable = true)
        String beneficiaryIfsc,
        @Schema(description = "The last four digits of the account.", nullable = true)
        String beneficiaryAccountLast4,
        @Schema(nullable = true)
        String note,
        @Schema(description = "Why it failed or was rejected.", nullable = true)
        String failureReason,
        @Schema(description = "Why the last attempt to reach the provider got no answer. Sync tries again safely.", nullable = true)
        String lastError,
        @Schema(nullable = true)
        String requestedByName,
        LocalDateTime requestedAt,
        @Schema(nullable = true)
        String authorizedByName,
        @Schema(nullable = true)
        LocalDateTime authorizedAt,
        @Schema(description = "True when the same system admin raised and authorized it.")
        boolean selfAuthorized,
        @Schema(nullable = true)
        LocalDateTime settledAt
) {}
