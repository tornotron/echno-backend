package org.tornotron.echno_backend.modules.bim.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Schema(description = "Declares the IFC about to be uploaded. Creates the next version and returns where to PUT it.")
public record PresignBimSourceRequest(
        @NotBlank @Size(max = 300) String filename,
        @Size(max = 100) String contentType,
        @NotNull @Positive Long fileSize
) {}
