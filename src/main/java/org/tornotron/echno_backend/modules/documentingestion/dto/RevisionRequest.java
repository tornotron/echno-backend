package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@Schema(description = "Marks a document as a revision of an earlier document of the same package, such as a revised "
        + "BOQ issued with a corrigendum.")
public record RevisionRequest(
        @Schema(description = "The earlier document this one replaces") @NotNull UUID supersedesDocumentId,
        @Schema(description = "The revision as printed, such as \"R1\" or \"Corrigendum 2\"", nullable = true)
        @Size(max = 100) String revisionLabel) {
}
