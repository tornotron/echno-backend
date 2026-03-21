package org.tornotron.echno_backend.material.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MaterialUpdateDto {

    @Size(max = 50, message = "SKU must not exceed 50 characters")
    private String sku;

    @Size(min = 1, max = 100, message = "material name must be between 1 and 100 characters")
    private String materialName;

    @Size(min = 1, max = 20, message = "unit must be between 1 and 20 characters")
    private String unit;

    private String description;

    private String hsn;

    private Double moq;

    private Double minStock;

    private Double maxStock;

    private Double safetyStock;

    private Double reorderLevel;
}
