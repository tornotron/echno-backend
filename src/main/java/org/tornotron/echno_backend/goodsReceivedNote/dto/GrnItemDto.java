package org.tornotron.echno_backend.goodsReceivedNote.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Schema(description = "A single line on a goods received note: the material received with its ordered and "
        + "received quantities and unit cost.")
public class GrnItemDto {

    @Schema(description = "Line id. Present on returned lines, omit when creating a GRN.", example = "902")
    private Long id;

    @Schema(description = "Material received on this line.", example = "310")
    @NotNull(message = "material ID is required")
    private Long materialId;

    @Schema(description = "Material name.", example = "Portland Cement 53 grade")
    private String materialName;

    @Schema(description = "Quantity ordered for this material.", example = "100")
    @NotNull(message = "ordered quantity is required")
    @Min(value = 0, message = "ordered quantity must be at least 0")
    private Integer orderedQuantity;

    @Schema(description = "Quantity actually received. This amount is posted into stock.", example = "95")
    @NotNull(message = "received quantity is required")
    @Min(value = 0, message = "received quantity must be at least 0")
    private Integer receivedQuantity;

    @Schema(description = "Unit cost of the received material.", example = "395.00")
    private BigDecimal unitCost;
}
