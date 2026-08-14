package org.tornotron.echno_backend.materialConsumption.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.materialConsumption.MaterialConsumption;
import org.tornotron.echno_backend.materialConsumption.dto.MaterialConsumptionDto;

/**
 * Maps {@link MaterialConsumption} to its DTO. The material, project, storage-location
 * and task associations are flattened to id + name; createdBy is mapped through
 * {@link EmployeeMapper} (which signs its attachment URLs).
 */
@Mapper(componentModel = "spring", uses = EmployeeMapper.class)
public interface MaterialConsumptionMapper {

    @Mapping(source = "material.id", target = "materialId")
    @Mapping(source = "material.materialName", target = "materialName")
    @Mapping(source = "project.id", target = "projectId")
    @Mapping(source = "project.projectName", target = "projectName")
    @Mapping(source = "storageLocation.id", target = "storageLocationId")
    @Mapping(source = "storageLocation.locationName", target = "storageLocationName")
    @Mapping(source = "task.id", target = "taskId")
    @Mapping(source = "task.title", target = "taskTitle")
    MaterialConsumptionDto toDto(MaterialConsumption consumption);
}
