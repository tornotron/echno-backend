package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.TenderProfile;

/**
 * A package's tender profile: the accepted facts {@code Project} has no field for. Each value is as
 * printed; a typed value beside it is set only when the text reads plainly as one. Provenance for each
 * field is at {@code GET /provenance?targetType=TENDER_PROFILE&targetId=<id>}.
 */
@Schema(description = "The accepted facts of a tender that a project has no field for, each as printed, with a "
        + "typed value where the text reads plainly as an amount, a date or a number of days.")
public record TenderProfileDto(UUID id, UUID packageId, Long projectId, String client, String estimatedValueText,
                               BigDecimal estimatedValue, String emdText, BigDecimal emdAmount, String tenderFeeText,
                               BigDecimal tenderFeeAmount, String bidDueText, LocalDateTime bidDueAt,
                               String bidValidityText, Integer bidValidityDays, String durationText, String consultant,
                               String engineer, String scope, LocalDateTime updatedAt) {

    public static TenderProfileDto of(TenderProfile t) {
        return new TenderProfileDto(t.getId(), t.getPackageId(), t.getProjectId(), t.getClient(),
                t.getEstimatedValueText(), t.getEstimatedValue(), t.getEmdText(), t.getEmdAmount(),
                t.getTenderFeeText(), t.getTenderFeeAmount(), t.getBidDueText(), t.getBidDueAt(),
                t.getBidValidityText(), t.getBidValidityDays(), t.getDurationText(), t.getConsultant(),
                t.getEngineer(), t.getScope(), t.getUpdatedAt());
    }
}
