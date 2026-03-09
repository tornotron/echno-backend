package org.tornotron.echno_backend.material.dto;

import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;

@Data
public class MaterialDto {

    private Long id;
    private String sku;
    private String materialName;
    private String unit;
    private EmployeeDto createdBy;
}
