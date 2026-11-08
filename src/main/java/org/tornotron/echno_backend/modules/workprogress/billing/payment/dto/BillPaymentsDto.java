package org.tornotron.echno_backend.modules.workprogress.billing.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillPaymentState;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.BillPaymentProvider;

@Schema(description = "A bill's payments and what is left to pay. The figures are the payable's, so a payment recorded in Finance shows here too.")
public record BillPaymentsDto(
        UUID billId,
        String billNumber,
        @Schema(description = "What would carry a new payment. MANUAL means a person pays from the bank and records the reference.")
        BillPaymentProvider provider,
        @Schema(nullable = true)
        Long payableId,
        @Schema(description = "Rupees; null until the bill is approved with something payable.", nullable = true)
        BigDecimal amountRecorded,
        @Schema(nullable = true)
        BigDecimal amountPaid,
        @Schema(nullable = true)
        BigDecimal amountDue,
        @Schema(nullable = true)
        BillPaymentState paymentState,
        @Schema(description = "True while a payment on this bill has not ended. A bill has one at a time.")
        boolean inFlight,
        @Schema(description = "Whether the contract has the account number and IFSC a provider needs.")
        boolean beneficiaryReady,
        Long projectId,
        @Schema(description = "The employee the project names as its payment authorizer; null when it names none.", nullable = true)
        Long authorizerEmployeeId,
        @Schema(nullable = true)
        String authorizerName,
        @Schema(description = "False when the employee named is no longer active, which leaves the project without one.")
        boolean authorizerActive,
        @Schema(description = "Whether the reader holds the payment authority on this project: authorizes, rejects, records and checks payments, though never one they raised.")
        boolean readerIsAuthorizer,
        @Schema(description = "Newest first.")
        List<BillPaymentDto> payments
) {}
