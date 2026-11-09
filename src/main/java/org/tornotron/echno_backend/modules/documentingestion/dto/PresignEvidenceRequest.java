package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.modules.documentingestion.domain.EvidenceSection;

@Schema(description = "Declares an evidence file about to be uploaded to the company profile: PDF, JPG, PNG, WEBP, "
        + "XLSX, XLS or DOCX.")
public record PresignEvidenceRequest(
        @NotNull EvidenceSection section,
        @Size(max = 300) String label,
        @NotBlank @Size(max = 300) String fileName,
        @Size(max = 100) String contentType,
        @NotNull @Positive Long fileSize
) {}
