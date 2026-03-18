package org.tornotron.echno_backend.DtoConversions;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.inventoryTransaction.CurrentStock;
import org.tornotron.echno_backend.inventoryTransaction.CurrentStockRepository;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.dto.StorageLocationDto;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

@Component
public class StorageLocationDtoConvertor {

    public static StorageLocationDto convertToDto(StorageLocation storageLocation,CurrentStockRepository currentStockRepository,Long organizationId) {
        if (storageLocation == null) {
            return null;
        }

        StorageLocationDto dto = new StorageLocationDto();
        dto.setId(storageLocation.getId());
        dto.setLocationName(storageLocation.getLocationName());
        dto.setLocationType(storageLocation.getLocationType());
        dto.setAddress(storageLocation.getAddress());
        dto.setActive(storageLocation.isActive());
        dto.setCapacity(storageLocation.getCapacity());
        dto.setLatitude(storageLocation.getLatitude());
        dto.setLongitude(storageLocation.getLongitude());
        dto.setStorageItemsCount(currentStockRepository.countDistinctMaterialsByStorageLocationIdAndOrganizationId(storageLocation.getId(),organizationId));


        // Project info
        if (storageLocation.getProject() != null) {
            dto.setProjectId(storageLocation.getProject().getId());
            dto.setProjectName(storageLocation.getProject().getProjectName());
        }

        return dto;
    }
}
