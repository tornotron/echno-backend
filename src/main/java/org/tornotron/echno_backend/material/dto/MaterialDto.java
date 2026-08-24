package org.tornotron.echno_backend.material.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import java.math.BigDecimal;

@Schema(description = "A material in the inventory catalogue, with its current aggregate stock.")
@Data
public class MaterialDto {
    @Schema(description = "Material id.", example = "12")
    private Long id;
    @Schema(description = "Stock keeping unit code.", example = "CEM-OPC53-001")
    private String sku;
    @Schema(description = "Name of the material.", example = "OPC 53 Grade Cement")
    private String materialName;
    @Schema(description = "Unit of measure for the material.", example = "bags")
    private String unit;
    @Schema(description = "Employee who created this material.")
    private EmployeeDto createdBy;
    @Schema(description = "Free-text description of the material.", example = "OPC 53 Grade cement, 50 kg bags")
    private String description;
    @Schema(description = "HSN code for the material, used on GST invoices.", example = "2523")
    private String hsn;
    @Schema(description = "Applicable GST percentage for the material, used on invoices.", example = "18.00")
    private BigDecimal gstRate;
    @Schema(description = "Opening stock quantity recorded when the material was created.", example = "500")
    private Double openingStock;
    @Schema(description = "Current aggregate stock across all projects and storage locations.", example = "340")
    private Double currentStock;
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
    @Schema(description = "Current stock valued at unit cost.", example = "139570.00")
    private BigDecimal stockValue;
}
