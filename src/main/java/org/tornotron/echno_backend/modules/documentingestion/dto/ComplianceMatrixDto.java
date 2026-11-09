package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.MatrixStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.PackageStage;
import org.tornotron.echno_backend.modules.documentingestion.service.QualificationComparison;

@Schema(description = "The Tender Compliance Matrix: each accepted qualification requirement beside the company "
        + "profile, with a status. It shows matches and gaps only and makes no bid or no-bid recommendation.")
public record ComplianceMatrixDto(
        UUID packageId,
        PackageStage stage,
        @Schema(description = "False at stage AWARDED, where qualification no longer applies; rows is then empty.")
        boolean shown,
        String notice,
        List<ComplianceMatrixRow> rows) {

    @Schema(description = "One requirement of the matrix.")
    public record ComplianceMatrixRow(
            UUID requirementId,
            @Schema(description = "TURNOVER, SIMILAR_PROJECTS, EXPERIENCE, EQUIPMENT or PERSONNEL.")
            String requirement,
            @Schema(example = "Turnover")
            String label,
            @Schema(description = "The requirement as the tender states it.", example = "Rs. 100 Cr")
            String tenderValue,
            @Schema(nullable = true, description = "What the company profile says; null when the field is blank.",
                    example = "₹132 Cr (2025-26)")
            String companyValue,
            MatrixStatus status,
            @Schema(description = "COMPARED from the figures, SET_BY_PERSON, AWAITING_PERSON for text no one has "
                    + "compared yet, PROFILE_BLANK, or NOT_READABLE when the tender's value is not a number.")
            QualificationComparison.Basis basis,
            @Schema(nullable = true)
            String reason,
            @Schema(nullable = true, description = "Who set the status, for a status set by a person.")
            Long statusSetBy,
            @Schema(nullable = true)
            LocalDateTime statusSetAt,
            @Schema(nullable = true)
            String statusNote,
            @Schema(description = "Uploaded evidence files of the matching profile section.")
            List<ComplianceMatrixEvidence> evidence,
            UUID candidateId,
            UUID documentId) {
    }

    @Schema(description = "An evidence file shown beside a row.")
    public record ComplianceMatrixEvidence(UUID id, @Schema(nullable = true) String label, String fileName) {
    }
}
