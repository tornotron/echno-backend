package org.tornotron.echno_backend.storageLocation.dto;

import lombok.Data;
import org.tornotron.echno_backend.storageLocation.enums.StorageLocationType;

@Data
public class StorageLocationDto {

    private Long id;
    private String locationName;
    private StorageLocationType locationType;
    private String address;
    private Long projectId;
    private String projectName;
    private String capacity;
    private boolean isActive;
    private Long storageItemsCount;
    private Float latitude;
    private Float longitude;
}
