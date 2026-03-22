package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.materialConsumption.MaterialConsumption;
import org.tornotron.echno_backend.materialConsumption.dto.MaterialConsumptionDto;

@Component
public class MaterialConsumptionDtoConvertor {

    public static MaterialConsumptionDto convertToDto(MaterialConsumption consumption, FileStorageService fileStorageService) {
        if (consumption == null) {
            return null;
        }

        MaterialConsumptionDto dto = new MaterialConsumptionDto();
        dto.setId(consumption.getId());
        dto.setConsumptionDate(consumption.getConsumptionDate());
        dto.setQuantity(consumption.getQuantity());
        dto.setConsumptionType(consumption.getConsumptionType());
        dto.setDetails(consumption.getDetails());

        // Material info
        if (consumption.getMaterial() != null) {
            dto.setMaterialId(consumption.getMaterial().getId());
            dto.setMaterialName(consumption.getMaterial().getMaterialName());
        }

        // Project info
        if (consumption.getProject() != null) {
            dto.setProjectId(consumption.getProject().getId());
            dto.setProjectName(consumption.getProject().getProjectName());
        }

        // Storage location info
        if (consumption.getStorageLocation() != null) {
            dto.setStorageLocationId(consumption.getStorageLocation().getId());
            dto.setStorageLocationName(consumption.getStorageLocation().getLocationName());
        }

        // Task info
        if (consumption.getTask() != null) {
            dto.setTaskId(consumption.getTask().getId());
            dto.setTaskTitle(consumption.getTask().getTitle());
        }

        // Created by
        if (consumption.getCreatedBy() != null) {
            dto.setCreatedBy(EmployeeDtoConvertor.convertEmployeeToDto(consumption.getCreatedBy(), fileStorageService));
        }

        return dto;
    }
}
