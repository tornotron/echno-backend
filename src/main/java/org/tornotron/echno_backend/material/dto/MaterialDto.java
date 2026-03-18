package org.tornotron.echno_backend.material.dto;

import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import java.math.BigDecimal;

@Data
public class MaterialDto {
    private Long id;
    private String sku;
    private String materialName;
    private String unit;
    private EmployeeDto createdBy;
    private String description;
    private String hsn;
    private Double openingStock;
    private Double currentStock;
    private Double moq;
    private Double minStock;
    private Double maxStock;
    private Double safetyStock;
    private Double reorderLevel;
    private BigDecimal stockValue;
}
