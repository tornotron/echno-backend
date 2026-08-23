package org.tornotron.echno_backend.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;
import org.tornotron.echno_backend.billing.enums.FeatureType;

@Schema(description = "A billing feature as returned by the API.")
@Value
@Builder
public class FeatureDto {
    @Schema(description = "Numeric id of the feature.", example = "12")
    Long id;
    @Schema(description = "Unique code identifying the feature.", example = "report-export")
    String code;
    @Schema(description = "Display name of the feature.", example = "PDF Report Export")
    String name;
    @Schema(description = "Longer description of what the feature grants.", example = "Allows exporting site reports as PDF documents.")
    String description;
    @Schema(description = "How the feature is measured.", example = "BOOLEAN")
    FeatureType featureType;
    @Schema(description = "Grouping used to organize features in the admin UI.", example = "reporting")
    String category;
    @Schema(description = "Whether the feature is currently active and assignable to plans.", example = "true")
    Boolean isActive;
}
