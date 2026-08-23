package org.tornotron.echno_backend.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.tornotron.echno_backend.billing.enums.FeatureType;

@Schema(description = "Payload to create or update a billing feature.")
@Data
public class FeatureCreateDto {

    @Schema(description = "Unique code identifying the feature.", example = "report-export")
    @NotBlank(message = "Feature code is required")
    @Size(max = 100, message = "Feature code must not exceed 100 characters")
    private String code;

    @Schema(description = "Display name of the feature.", example = "PDF Report Export")
    @NotBlank(message = "Feature name is required")
    private String name;

    @Schema(description = "Longer description of what the feature grants.", example = "Allows exporting site reports as PDF documents.")
    private String description;

    @Schema(description = "How the feature is measured: a plain toggle, a fixed quota, or a metered count.", example = "BOOLEAN")
    @NotBlank(message = "Feature type is required")
    private String featureType;

    @Schema(description = "Grouping used to organize features in the admin UI.", example = "reporting")
    @Size(max = 50, message = "Category must not exceed 50 characters")
    private String category;
}
