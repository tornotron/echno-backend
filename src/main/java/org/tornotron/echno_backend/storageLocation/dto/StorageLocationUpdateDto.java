package org.tornotron.echno_backend.storageLocation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "Payload to partially update a storage location. Only the fields present in the "
        + "request are changed.")
@Data
public class StorageLocationUpdateDto {

    @Schema(description = "New name for the storage location.", example = "Central Warehouse - Chennai")
    @Size(min = 1, max = 100, message = "location name must be between 1 and 100 characters")
    private String locationName;

    @Schema(description = "New type for the storage location. One of PROJECT_SITE, WAREHOUSE, GODOWN, "
            + "HEAD_OFFICE, PROCESSING_PLANT or OTHERS.", example = "GODOWN")
    private String locationType;

    @Schema(description = "New postal address for the storage location.", example = "Plot 14, Ambattur Industrial Estate, Chennai")
    @Size(max = 255, message = "address must not exceed 255 characters")
    private String address;

    @Schema(description = "New storage capacity, free text since units vary by material.", example = "6000 sq ft")
    private String capacity;

    @Schema(description = "New active status for the storage location.", example = "true")
    private Boolean isActive;

    @Schema(description = "New latitude of the storage location.", example = "13.0827")
    private Float latitude;

    @Schema(description = "New longitude of the storage location.", example = "80.2707")
    private Float longitude;

    @Schema(description = "New project id this location serves.", example = "12")
    private Long projectId;
}
