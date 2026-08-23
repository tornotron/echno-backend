package org.tornotron.echno_backend.material.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;

@Schema(description = "A material with its stock scoped to the organization, a project or a storage "
        + "location, depending on which lookup was used.")
@Data
public class MaterialWithStockDto {

    @Schema(description = "Material id.", example = "12")
    private Long id;
    @Schema(description = "Stock keeping unit code.", example = "TMT-12MM-001")
    private String sku;
    @Schema(description = "Name of the material.", example = "TMT Bar 12mm")
    private String materialName;
    @Schema(description = "Unit of measure for the material.", example = "kg")
    private String unit;
    @Schema(description = "Current stock at the requested scope.", example = "1250")
    private Double currentStock;
    @Schema(description = "Current stock valued at unit cost.", example = "78125.00")
    private BigDecimal stockValue;
}
