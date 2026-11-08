package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.modules.documentingestion.domain.PackageStage;

@Schema(description = "A new package of tender files. With no project it can create one later; with one, it fills that project.")
public record CreatePackageRequest(
        @NotBlank @Size(max = 200) String name,
        Long projectId,
        @Schema(description = "TENDER while bidding, AWARDED once won. Defaults to TENDER") PackageStage stage
) {}
