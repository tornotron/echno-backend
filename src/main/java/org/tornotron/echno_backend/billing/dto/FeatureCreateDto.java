package org.tornotron.echno_backend.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.tornotron.echno_backend.billing.enums.FeatureType;

@Data
public class FeatureCreateDto {

    @NotBlank(message = "Feature code is required")
    @Size(max = 100, message = "Feature code must not exceed 100 characters")
    private String code;

    @NotBlank(message = "Feature name is required")
    private String name;

    private String description;

    @NotBlank(message = "Feature type is required")
    private String featureType;

    @Size(max = 50, message = "Category must not exceed 50 characters")
    private String category;
}
