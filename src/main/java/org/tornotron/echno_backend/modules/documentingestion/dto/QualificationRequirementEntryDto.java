package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.MatrixStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.QualificationRequirementEntry;
import org.tornotron.echno_backend.modules.documentingestion.extraction.qualification.QualificationRequirementType;

/**
 * One accepted qualification requirement. Provenance for each field is at
 * {@code GET /provenance?targetType=QUALIFICATION_REQUIREMENT&targetId=<id>}.
 */
@Schema(description = "A bidder qualification requirement of the tender, as printed and accepted by a person.")
public record QualificationRequirementEntryDto(
        UUID id,
        UUID packageId,
        @Schema(description = "TURNOVER, SIMILAR_PROJECTS, EXPERIENCE, EQUIPMENT or PERSONNEL.", example = "TURNOVER")
        String requirement,
        @Schema(example = "Turnover")
        String label,
        @Schema(description = "The amount, count, years or list as printed.", example = "Rs. 100 Cr")
        String value,
        @Schema(nullable = true, description = "Similar projects only: the value each work must reach, as printed.")
        String minValue,
        @Schema(nullable = true, description = "The years counted over, as printed.")
        String period,
        @Schema(description = "Whether the matrix compares it automatically; otherwise a person sets the status.")
        boolean comparedAutomatically,
        @Schema(nullable = true, description = "NIT, ITB or ELIGIBILITY.", example = "NIT")
        String sourceClass,
        UUID documentId,
        UUID candidateId,
        @Schema(nullable = true, description = "The status a person set, for equipment or personnel.")
        MatrixStatus personStatus,
        @Schema(nullable = true)
        String personNote,
        @Schema(nullable = true)
        Long statusSetBy,
        @Schema(nullable = true)
        LocalDateTime statusSetAt,
        LocalDateTime updatedAt) {

    public static QualificationRequirementEntryDto of(QualificationRequirementEntry q) {
        QualificationRequirementType type = QualificationRequirementType.valueOf(q.getRequirement());
        return new QualificationRequirementEntryDto(q.getId(), q.getPackageId(), q.getRequirement(), type.label(),
                q.getValueText(), q.getMinValueText(), q.getPeriodText(), type.numeric(), q.getSourceClass(),
                q.getDocumentId(), q.getCandidateId(), q.getPersonStatus(), q.getPersonNote(), q.getStatusSetBy(),
                q.getStatusSetAt(), q.getUpdatedAt());
    }
}
