package org.tornotron.echno_backend.modules.vision.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.modules.vision.domain.CvModelFamily;
import org.tornotron.echno_backend.modules.vision.domain.CvModelTask;

@Schema(description = "A new model family.")
public record CreateCvModelRequest(
        @Schema(description = "The stable name producers send as modelName, for example ppe.")
        @NotBlank @Size(max = 50) @Pattern(regexp = "[a-z][a-z0-9-]*") String key,
        @NotBlank @Size(max = 100) String name,
        @NotNull CvModelTask task,
        @NotNull CvModelFamily family
) {}
