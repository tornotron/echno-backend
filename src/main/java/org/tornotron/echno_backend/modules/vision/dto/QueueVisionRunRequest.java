package org.tornotron.echno_backend.modules.vision.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

@Schema(description = "Runs models on evidence images now. Name exactly one of inspectionId, observationId and "
        + "attachmentIds.")
public record QueueVisionRunRequest(
        @Schema(description = "Every evidence image of this inspection.", nullable = true) UUID inspectionId,
        @Schema(description = "Every evidence image of this observation.", nullable = true) UUID observationId,
        @Schema(description = "These inspection or observation evidence images, up to 200.", nullable = true)
        @Size(max = 200) List<@NotNull Long> attachmentIds,
        @Schema(description = "The model families to run. When absent, the organization's settings decide.",
                nullable = true)
        @Size(max = 20) List<@NotBlank @Size(max = 50) String> modelKeys,
        @Schema(description = "Run a version again on an image it already ran on.", nullable = true) Boolean rerun
) {}
