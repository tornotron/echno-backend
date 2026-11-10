package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.ObligationEntry;

/**
 * One row of the Contract Obligation Register. Provenance for each field is at
 * {@code GET /provenance?targetType=OBLIGATION&targetId=<id>}.
 */
@Schema(description = "One obligation found in the conditions of contract with a stated deadline, as printed and "
        + "accepted by a person. The register lists the obligations found, not every obligation of the contract, "
        + "and Echno does not interpret the clause.")
public record ObligationEntryDto(
        UUID id,
        UUID packageId,
        Long projectId,
        @Schema(nullable = true, example = "52.1")
        String clause,
        @Schema(nullable = true, example = "Contractor")
        String responsibleParty,
        @Schema(nullable = true, example = "performance security")
        String topic,
        @Schema(nullable = true, description = "The event the time runs from.", example = "receipt of the Letter of Acceptance")
        String trigger,
        @Schema(description = "The deadline as printed.", example = "21 days")
        String deadline,
        @Schema(nullable = true, description = "The deadline in days, only when it reads plainly as calendar days or "
                + "weeks.", example = "21")
        Integer deadlineDays,
        @Schema(nullable = true, example = "Performance Security")
        String requiredDocument,
        @Schema(nullable = true, description = "The money at stake, as printed.")
        String financialImpact,
        @Schema(nullable = true, description = "GCC, SCC or CONTRACT_DATA.", example = "GCC")
        String sourceClass,
        UUID documentId,
        UUID candidateId,
        @Schema(nullable = true, description = "The project risk raised from this obligation, if any.")
        UUID riskId,
        LocalDateTime updatedAt) {

    public static ObligationEntryDto of(ObligationEntry o) {
        return new ObligationEntryDto(o.getId(), o.getPackageId(), o.getProjectId(), o.getClause(),
                o.getResponsibleParty(), o.getTopic(), o.getTriggerEvent(), o.getDeadlineText(), o.getDeadlineDays(),
                o.getRequiredDocument(), o.getFinancialImpact(), o.getSourceClass(), o.getDocumentId(),
                o.getCandidateId(), o.getRiskId(), o.getUpdatedAt());
    }
}
