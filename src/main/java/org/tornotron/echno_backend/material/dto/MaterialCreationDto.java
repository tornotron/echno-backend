package org.tornotron.echno_backend.material.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MaterialCreationDto {

    @Size(max = 50, message = "SKU must not exceed 50 characters")
    private String sku;

    @NotBlank(message = "material name is required")
    @Size(min = 1, max = 100, message = "material name must be between 1 and 100 characters")
    private String materialName;

    @NotBlank(message = "unit is required")
    @Size(min = 1, max = 20, message = "unit must be between 1 and 20 characters")
    private String unit;
}
