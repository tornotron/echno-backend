package org.tornotron.echno_backend.modules.__MODULE_PKG__.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Creates a __MODULE_NAME__ entry in the current tenant.")
public record Create__MODULE_PASCAL__EntryRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 2000) String notes
) {}
