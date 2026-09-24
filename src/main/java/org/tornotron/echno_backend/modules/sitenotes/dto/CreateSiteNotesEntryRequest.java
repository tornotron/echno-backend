package org.tornotron.echno_backend.modules.sitenotes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Creates a Site Notes entry in the current tenant.")
public record CreateSiteNotesEntryRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 2000) String notes
) {}
