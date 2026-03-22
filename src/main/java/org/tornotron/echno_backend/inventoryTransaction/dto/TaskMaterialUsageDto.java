package org.tornotron.echno_backend.inventoryTransaction.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskMaterialUsageDto {

    private Long taskId;
    private String taskTitle;
    private List<MaterialUsageItem> materials;
    private Double totalQuantityUsed;
    private BigDecimal totalCost;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MaterialUsageItem {
        private Long materialId;
        private String materialName;
        private String unit;
        private Double totalQuantityUsed;
        private BigDecimal totalCost;
    }
}
