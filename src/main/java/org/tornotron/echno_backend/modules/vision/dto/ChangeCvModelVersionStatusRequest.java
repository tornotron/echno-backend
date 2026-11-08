package org.tornotron.echno_backend.modules.vision.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.modules.vision.domain.CvModelVersionStatus;

@Schema(description = "Activate or retire a model version. Activating retires the version active before it.")
public record ChangeCvModelVersionStatusRequest(
        @Schema(description = "ACTIVE or RETIRED.")
        @NotNull CvModelVersionStatus status,
        @Schema(description = "Why, for the log.", nullable = true)
        @Size(max = 500) String note
) {}
