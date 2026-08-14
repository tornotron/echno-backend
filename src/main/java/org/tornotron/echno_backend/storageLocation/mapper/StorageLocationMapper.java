package org.tornotron.echno_backend.storageLocation.mapper;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.inventoryTransaction.CurrentStockRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.dto.StorageLocationDto;

/**
 * Maps {@link StorageLocation} to its DTO. Project is flattened to id + name; the
 * distinct-materials count is computed from {@link CurrentStockRepository} for the
 * current tenant in an {@code @AfterMapping} hook, matching the previous converter.
 */
@Mapper(componentModel = "spring")
public abstract class StorageLocationMapper {

    @Autowired
    protected CurrentStockRepository currentStockRepository;

    @Mapping(source = "project.id", target = "projectId")
    @Mapping(source = "project.projectName", target = "projectName")
    @Mapping(target = "storageItemsCount", ignore = true) // computed in fillItemsCount
    public abstract StorageLocationDto toDto(StorageLocation storageLocation);

    @AfterMapping
    protected void fillItemsCount(StorageLocation source, @MappingTarget StorageLocationDto dto) {
        dto.setStorageItemsCount(currentStockRepository
                .countDistinctMaterialsByStorageLocationIdAndOrganizationId(source.getId(), TenantContext.getCurrentOrgId()));
    }
}
