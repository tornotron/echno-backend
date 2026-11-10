package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.ContractTermEntry;
import org.tornotron.echno_backend.modules.documentingestion.extraction.contract.ContractTerm;

/**
 * One accepted contract term of a package. Provenance for each field is at
 * {@code GET /provenance?targetType=CONTRACT_TERM&targetId=<id>}.
 */
@Schema(description = "One accepted contract term of a package, as printed, with the clause and the part of the "
        + "documents it came from. These describe the main contract as the tender states it; Echno does not "
        + "interpret the clause.")
public record ContractTermEntryDto(
        UUID id,
        UUID packageId,
        Long projectId,
        @Schema(description = "The term's key, such as ld_rate.", example = "ld_rate")
        String term,
        @Schema(description = "The term's name.", example = "LD rate")
        String label,
        @Schema(description = "The value as printed.", example = "0.5% of the Contract Price per week of delay")
        String value,
        @Schema(nullable = true, description = "The clause number where it is stated.", example = "47.1")
        String clause,
        @Schema(nullable = true, description = "GCC, SCC or CONTRACT_DATA.", example = "SCC")
        String sourceClass,
        UUID documentId,
        UUID candidateId,
        @Schema(nullable = true, description = "The project risk raised from this term, if any.")
        UUID riskId,
        LocalDateTime updatedAt) {

    public static ContractTermEntryDto of(ContractTermEntry t) {
        return new ContractTermEntryDto(t.getId(), t.getPackageId(), t.getProjectId(), t.getTerm(),
                ContractTerm.of(t.getTerm()).map(ContractTerm::label).orElse(t.getTerm()), t.getValueText(),
                t.getClause(), t.getSourceClass(), t.getDocumentId(), t.getCandidateId(), t.getRiskId(),
                t.getUpdatedAt());
    }
}
