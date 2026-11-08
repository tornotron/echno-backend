package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Schema(description = "Declares a file about to be uploaded to a package: PDF, XLSX, XLS, DOCX, DXF, DWG or ZIP.")
public record PresignDocumentRequest(
        @NotBlank @Size(max = 300) String fileName,
        @Size(max = 100) String contentType,
        @NotNull @Positive Long fileSize
) {}
