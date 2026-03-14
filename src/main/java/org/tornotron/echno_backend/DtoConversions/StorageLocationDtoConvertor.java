package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.dto.StorageLocationDto;

@Component
public class StorageLocationDtoConvertor {

    public static StorageLocationDto convertToDto(StorageLocation storageLocation) {
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

        // Project info
        if (storageLocation.getProject() != null) {
            dto.setProjectId(storageLocation.getProject().getId());
            dto.setProjectName(storageLocation.getProject().getProjectName());
        }

        return dto;
    }
}
