package org.tornotron.echno_backend.modules.bim.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Registers a BIM model on a project. Versions are added by uploading an IFC.")
public record CreateBimModelRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 2000) String description
) {}
