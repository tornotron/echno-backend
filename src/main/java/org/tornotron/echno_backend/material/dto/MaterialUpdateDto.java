package org.tornotron.echno_backend.material.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.math.BigDecimal;

@Schema(description = "Payload to partially update a material. Fields left null are left unchanged.")
@Data
public class MaterialUpdateDto {

    @Schema(description = "Stock keeping unit code, unique within the organization.", example = "CEM-OPC53-001")
    @Size(max = 50, message = "SKU must not exceed 50 characters")
    private String sku;

    @Schema(description = "Name of the material.", example = "OPC 53 Grade Cement")
    @Size(min = 1, max = 100, message = "material name must be between 1 and 100 characters")
    private String materialName;

    @Schema(description = "Unit of measure for the material.", example = "bags")
    @Size(min = 1, max = 20, message = "unit must be between 1 and 20 characters")
    private String unit;

    @Schema(description = "Free-text description of the material.", example = "OPC 53 Grade cement, 50 kg bags")
    private String description;

    @Schema(description = "HSN code for the material, used on GST invoices.", example = "2523")
    private String hsn;

    @Schema(description = "Applicable GST percentage for the material, used on invoices.", example = "18.00")
    private BigDecimal gstRate;

    @Schema(description = "Minimum order quantity from the supplier.", example = "100")
    private Double moq;

    @Schema(description = "Minimum stock level before the material is considered low.", example = "200")
    private Double minStock;

    @Schema(description = "Maximum stock level to hold at any storage location.", example = "2000")
    private Double maxStock;

    @Schema(description = "Safety stock buffer kept in reserve below the minimum stock level.", example = "50")
    private Double safetyStock;

    @Schema(description = "Stock level at which a reorder should be triggered.", example = "300")
    private Double reorderLevel;
}
