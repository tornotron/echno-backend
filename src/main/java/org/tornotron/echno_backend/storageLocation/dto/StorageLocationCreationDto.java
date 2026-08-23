package org.tornotron.echno_backend.storageLocation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "Payload to create a storage location where inventory is held, such as a site "
        + "store, a central warehouse or a godown.")
@Data
public class StorageLocationCreationDto {

    @Schema(description = "Name of the storage location.", example = "Central Warehouse - Chennai")
    @NotBlank(message = "location name is required")
    @Size(min = 1, max = 100, message = "location name must be between 1 and 100 characters")
    private String locationName;

    @Schema(description = "Type of storage location. One of PROJECT_SITE, WAREHOUSE, GODOWN, "
            + "HEAD_OFFICE, PROCESSING_PLANT or OTHERS.", example = "WAREHOUSE")
    @NotBlank(message = "location type is required")
    private String locationType;

    @Schema(description = "Postal address of the storage location.", example = "Plot 14, Ambattur Industrial Estate, Chennai")
    @Size(max = 255, message = "address must not exceed 255 characters")
    private String address;

    @Schema(description = "Storage capacity, free text since units vary by material.", example = "5000 sq ft")
    private String capacity;

    @Schema(description = "Whether the storage location is currently active.", example = "true")
    private boolean isActive;

    @Schema(description = "Id of the project this location serves. Optional, a central warehouse or "
            + "godown may serve more than one project.", example = "12")
    private Long projectId;

    @Schema(description = "Latitude of the storage location.", example = "13.0827")
    private Float latitude;

    @Schema(description = "Longitude of the storage location.", example = "80.2707")
    private Float longitude;
}
