package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.QualityRequirementEntry;
import org.tornotron.echno_backend.modules.documentingestion.extraction.quality.QualityFields;

/**
 * One row of a project's inspection and test plan, read from its specification, with the quote and place
 * behind each value. Provenance for each field is at
 * {@code GET /provenance?targetType=QUALITY_REQUIREMENT&targetId=<id>}.
 */
@Schema(description = "One quality requirement as the specification prints it and a person accepted it, with "
        + "citations. A frequency or acceptance criterion the specification does not state is null and named in "
        + "notStated.")
public record QualityRequirementEntryDto(
        UUID id,
        UUID packageId,
        @Schema(nullable = true) Long projectId,
        @Schema(description = "The material or subject as printed.", example = "Concrete")
        String subject,
        @Schema(nullable = true, example = "Concreting") String activity,
        @Schema(nullable = true, description = "The organization's inspection trade the activity was matched to.")
        UUID tradeId,
        @Schema(nullable = true, example = "rcc") String tradeCode,
        @Schema(nullable = true, example = "RCC") String tradeName,
        @Schema(nullable = true, example = "Concrete") String material,
        @Schema(nullable = true, example = "M25") String grade,
        @Schema(description = "Standards cited, as printed.", example = "[\"IS 456:2000\"]")
        List<String> standards,
        @Schema(nullable = true, example = "Cube compressive strength") String test,
        @Schema(nullable = true, description = "As printed; null when the specification does not state one.")
        String frequency,
        @Schema(nullable = true, description = "As printed; null when the specification does not state one.")
        String acceptanceCriterion,
        @Schema(nullable = true, example = "Pre-pour inspection") String holdOrWitnessPoint,
        @Schema(description = "Documents to be given, as printed.") List<String> requiredDocuments,
        @Schema(description = "Fields the specification does not state: frequency, acceptance_criterion.")
        List<String> notStated,
        @Schema(nullable = true, example = "SPECIFICATION") String sourceClass,
        @Schema(nullable = true) UUID documentId,
        UUID candidateId,
        @Schema(description = "Where each value came from: page, box and quote.") List<SourceRefDto> citations,
        @Schema(nullable = true, description = "The checklist template the row was copied into, if it was.")
        UUID templateId,
        @Schema(nullable = true) UUID templateItemId,
        @Schema(nullable = true, description = "The template's revision after the copy.") Integer templateVersion,
        @Schema(nullable = true) LocalDateTime copiedAt,
        LocalDateTime updatedAt) {

    public static QualityRequirementEntryDto of(QualityRequirementEntry q, List<SourceRefDto> citations) {
        List<String> notStated = new ArrayList<>();
        if (q.getFrequency() == null) {
            notStated.add(QualityFields.FREQUENCY);
        }
        if (q.getAcceptanceCriterion() == null) {
            notStated.add(QualityFields.ACCEPTANCE_CRITERION);
        }
        return new QualityRequirementEntryDto(q.getId(), q.getPackageId(), q.getProjectId(), q.getSubject(),
                q.getActivity(), q.getTradeId(), q.getTradeCode(), q.getTradeName(), q.getMaterial(), q.getGrade(),
                q.getStandards() == null ? List.of() : List.copyOf(q.getStandards()), q.getTest(), q.getFrequency(),
                q.getAcceptanceCriterion(), q.getHoldOrWitnessPoint(),
                q.getRequiredDocuments() == null ? List.of() : List.copyOf(q.getRequiredDocuments()), notStated,
                q.getSourceClass(), q.getDocumentId(), q.getCandidateId(), citations, q.getTemplateId(),
                q.getTemplateItemId(), q.getTemplateVersion(), q.getCopiedAt(), q.getUpdatedAt());
    }
}
