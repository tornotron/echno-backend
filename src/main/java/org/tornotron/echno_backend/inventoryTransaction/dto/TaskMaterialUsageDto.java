package org.tornotron.echno_backend.inventoryTransaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Material consumption rolled up for a single task: the materials used with their "
        + "quantity and cost, plus the task totals.")
public class TaskMaterialUsageDto {

    @Schema(description = "Task id.", example = "884")
    private Long taskId;

    @Schema(description = "Task title.", example = "Second floor slab casting")
    private String taskTitle;

    @Schema(description = "Per-material usage lines for this task.")
    private List<MaterialUsageItem> materials;

    @Schema(description = "Total quantity consumed across all materials on this task.", example = "180.0")
    private Double totalQuantityUsed;

    @Schema(description = "Total cost of material consumed on this task.", example = "71100.00")
    private BigDecimal totalCost;

    @Schema(description = "Consumption of one material on the task, with quantity and cost.")
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MaterialUsageItem {
        @Schema(description = "Material id.", example = "310")
        private Long materialId;

        @Schema(description = "Material name.", example = "Portland Cement 53 grade")
        private String materialName;

        @Schema(description = "Unit of measure the quantity is expressed in.", example = "bag")
        private String unit;

        @Schema(description = "Quantity of this material consumed on the task.", example = "45.0")
        private Double totalQuantityUsed;

        @Schema(description = "Cost of this material consumed on the task.", example = "17775.00")
        private BigDecimal totalCost;
    }
}
