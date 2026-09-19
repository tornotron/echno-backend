package org.tornotron.echno_backend.modules.toolboxtalks.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Creates a Toolbox Talks entry in the current tenant.")
public record CreateToolboxTalksEntryRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 2000) String notes
) {}
