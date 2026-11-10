package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.CompanyEvidence;
import org.tornotron.echno_backend.modules.documentingestion.domain.EvidenceSection;

@Schema(description = "An evidence file of the company profile, such as a turnover certificate or a completion "
        + "certificate. Shown in the compliance matrix beside the rows of its section once uploaded.")
public record CompanyEvidenceDto(
        UUID id,
        EvidenceSection section,
        @Schema(nullable = true, example = "Auditor's turnover certificate 2025-26")
        String label,
        String fileName,
        String contentType,
        Long sizeBytes,
        @Schema(description = "False until the upload is registered.")
        boolean uploaded,
        LocalDateTime createdAt) {

    public static CompanyEvidenceDto of(CompanyEvidence e) {
        return new CompanyEvidenceDto(e.getId(), e.getSection(), e.getLabel(), e.getFileName(), e.getContentType(),
                e.getSizeBytes(), e.isUploaded(), e.getCreatedAt());
    }
}
