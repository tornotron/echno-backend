package org.tornotron.echno_backend.stockAdjustment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Why an approver refused a stock adjustment.
 *
 * <p>The reason is required. A rejection exists so that a refused correction stays on the
 * record instead of being deleted away, and a rejection carrying no reason records only that
 * somebody said no, which is the part nobody needed written down. The same standard the
 * posting path applies to a movement, that it must say why it happened, is applied to the
 * decision not to make one.
 *
 * <p>The 500 character bound is the width of the {@code rejection_reason} column, so a longer
 * reason is refused as a validation error rather than by the database.
 */
@Schema(description = "Payload to reject a stock adjustment, carrying the reason it was refused.")
public record StockAdjustmentRejectionRequest(
        @Schema(description = "Why the adjustment is being refused. Required.",
                example = "Variance not supported by the count sheet")
        @NotBlank @Size(max = 500) String reason
) {}
