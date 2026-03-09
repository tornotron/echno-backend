package org.tornotron.echno_backend.materialConsumption.dto;

import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.materialConsumption.enums.MaterialConsumptionType;
import org.tornotron.echno_backend.user.dto.UserDto;

import java.time.LocalDateTime;

@Data
public class MaterialConsumptionDto {

    private Long id;
    private LocalDateTime consumptionDate;
    private Long materialId;
    private String materialName;
    private Integer quantity;
    private MaterialConsumptionType consumptionType;
    private String details;
    private EmployeeDto createdBy;
}
