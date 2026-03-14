package org.tornotron.echno_backend.storageLocation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class StorageLocationCreationDto {

    @NotBlank(message = "location name is required")
    @Size(min = 1, max = 100, message = "location name must be between 1 and 100 characters")
    private String locationName;

    @NotBlank(message = "location type is required")
    private String locationType;

    @Size(max = 255, message = "address must not exceed 255 characters")
    private String address;

    private String capacity;

    private boolean isActive;

    private Long projectId;
}
