package org.tornotron.echno_backend.material.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Schema(description = "The figures a materials dashboard strip is built from, totalled in the "
        + "database over the whole scope rather than over the rows a client happens to hold. "
        + "Every figure is for the same scope: the organization, or one project when projectId "
        + "is given.")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MaterialStockSummaryDto {

    @Schema(description = "The project these figures were totalled within, null when the whole "
            + "organization was totalled.", example = "5", nullable = true)
    private Long projectId;

    @Schema(description = "How many materials the figures cover. At organization scope this is "
            + "the catalogue size. At project scope it is how many materials the project carries "
            + "a balance row for, which is the same set the value is summed over.", example = "743")
    private long materialCount;

    @Schema(description = "How many distinct units of measure those materials are held in.",
            example = "9")
    private long distinctUnits;

    @Schema(description = "The value of the stock on hand, summed over every balance row in "
            + "scope at its running weighted-average cost. Zero when nothing is held.",
            example = "50148300.00")
    private BigDecimal totalStockValue;

    @Schema(description = "How many balance rows hold a quantity the total could not price, "
            + "because the receipts behind them carried no unit cost and so added quantity at no "
            + "value. Those rows are in the total at the zero they actually hold, so a non-zero "
            + "count here means the total is an understatement and says by how many holdings. "
            + "Zero means every holding in scope is priced and the total is complete.",
            example = "0")
    private long unvaluedHoldingCount;
}
