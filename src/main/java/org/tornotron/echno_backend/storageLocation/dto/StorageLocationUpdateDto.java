package org.tornotron.echno_backend.storageLocation.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class StorageLocationUpdateDto {

    @Size(min = 1, max = 100, message = "location name must be between 1 and 100 characters")
    private String locationName;

    private String locationType;

    @Size(max = 255, message = "address must not exceed 255 characters")
    private String address;

    private String capacity;

    private Boolean isActive;

    private Float latitude;

    private Float longitude;

    private Long projectId;
}
