package org.tornotron.echno_backend.inventoryTransaction.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.inventoryTransaction.InventoryTransaction;
import org.tornotron.echno_backend.inventoryTransaction.dto.InventoryTransactionDto;
import org.tornotron.echno_backend.inventoryTransaction.dto.MaterialMovementHistoryDto;

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

    /**
     * Condenses a ledger row to a movement-history timeline entry. The movement direction is
     * read from the transaction type's {@code stockEffect}, so the sign metadata lives in one
     * place rather than being inferred at the call site.
     */
    @Mapping(source = "transactionType.stockEffect", target = "direction")
    @Mapping(source = "storageLocation.id", target = "storageLocationId")
    @Mapping(source = "storageLocation.locationName", target = "storageLocationName")
    @Mapping(source = "project.id", target = "projectId")
    @Mapping(source = "project.projectName", target = "projectName")
    MaterialMovementHistoryDto toMovementHistoryDto(InventoryTransaction transaction);
}
