package org.tornotron.echno_backend.modules.vision.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

@Schema(description = "A trained version of a model family. It lands DRAFT; a person activates it after "
        + "reading its report.")
public record RegisterCvModelVersionRequest(
        @Schema(description = "Semantic version, unique within the family.")
        @NotBlank @Size(max = 50) @Pattern(regexp = "\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?") String version,
        @NotEmpty @Size(max = 100) @Valid List<CvModelClassDto> classes,
        @Schema(description = "SPDX id of the licence the weights carry.")
        @NotBlank @Size(max = 50) String licence,
        @Schema(description = "What the training started from, for example \"rf-detr-nano COCO (Apache-2.0)\".")
        @NotBlank @Size(max = 200) String baseWeights,
        @Schema(description = "Where the worker downloads the weights, s3://bucket/key.")
        @NotBlank @Size(max = 500) @Pattern(regexp = "s3://\\S+") String weightsUri,
        @Schema(description = "Lower-case hex SHA-256 of the weights file.")
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String weightsSha256,
        @NotBlank @Size(max = 30) String runtime,
        @Positive int inputSize,
        @Schema(description = "Dataset release it was trained on.", nullable = true)
        @Size(max = 50) String trainingRelease,
        @Schema(description = "Where the training run record is.", nullable = true)
        @Size(max = 500) String trainingRunUri,
        @Schema(description = "Summary from the run record. Required before activation.", nullable = true)
        Map<String, Object> metrics,
        @Schema(description = "Where the evaluation report is. Required before activation.", nullable = true)
        @Size(max = 500) String evalReportUri
) {}
