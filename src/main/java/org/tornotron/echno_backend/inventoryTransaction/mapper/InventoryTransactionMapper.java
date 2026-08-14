package org.tornotron.echno_backend.inventoryTransaction.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransaction;
import org.tornotron.echno_backend.inventoryTransaction.dto.InventoryTransactionDto;

/**
 * Maps {@link InventoryTransaction} to its DTO. The material/project/storage-location/
 * task associations flatten to id + name; createdBy is mapped through {@link EmployeeMapper}.
 */
@Mapper(componentModel = "spring", uses = EmployeeMapper.class)
public interface InventoryTransactionMapper {

    @Mapping(source = "material.id", target = "materialId")
    @Mapping(source = "material.materialName", target = "materialName")
    @Mapping(source = "project.id", target = "projectId")
    @Mapping(source = "project.projectName", target = "projectName")
    @Mapping(source = "storageLocation.id", target = "storageLocationId")
    @Mapping(source = "storageLocation.locationName", target = "storageLocationName")
    @Mapping(source = "task.id", target = "taskId")
    @Mapping(source = "task.title", target = "taskTitle")
    InventoryTransactionDto toDto(InventoryTransaction transaction);
}
