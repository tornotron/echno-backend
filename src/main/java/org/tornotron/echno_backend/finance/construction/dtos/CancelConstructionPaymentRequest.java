package org.tornotron.echno_backend.finance.construction.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Why a payment voucher is being voided.
 *
 * <p>The reason is required rather than optional. A voucher exists to explain a payment, so
 * voiding one without saying what was wrong with it leaves nothing behind but a gap, and on a
 * voucher that had been verified it is also the only record of why somebody's check was set
 * aside. The same rule {@code StockAdjustmentService.reject} applies to a refused adjustment.
 *
 * @param reason What was wrong with the voucher.
 */
@Schema(description = "Why a payment voucher is being voided. Required: a cancelled voucher that "
        + "does not say what was wrong with it explains nothing, and on a verified voucher this is "
        + "the only record of why the verification was set aside.")
public record CancelConstructionPaymentRequest(
        @Schema(description = "What was wrong with the voucher.",
                example = "Duplicate of CPMT-000118, raised twice for the same invoice")
        @NotBlank @Size(max = 1000) String reason
) {}
