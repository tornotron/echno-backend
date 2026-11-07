package org.tornotron.echno_backend.modules.vision.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.tornotron.echno_backend.modules.vision.domain.CvModelVersionStatus;

@Schema(description = "One trained version of a model family.")
public record CvModelVersionDto(
        UUID id,
        String modelKey,
        String version,
        CvModelVersionStatus status,
        List<CvModelClassDto> classes,
        String licence,
        String baseWeights,
        String weightsUri,
        String weightsSha256,
        String runtime,
        int inputSize,
        @Schema(nullable = true) String trainingRelease,
        @Schema(nullable = true) String trainingRunUri,
        @Schema(nullable = true) Map<String, Object> metrics,
        @Schema(nullable = true) String evalReportUri,
        @Schema(description = "User who registered it; null for a service account with no user row.", nullable = true)
        Long registeredById,
        @Schema(nullable = true) LocalDateTime activatedAt,
        @Schema(nullable = true) LocalDateTime retiredAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
