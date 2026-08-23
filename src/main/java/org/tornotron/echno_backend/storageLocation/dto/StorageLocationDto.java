package org.tornotron.echno_backend.storageLocation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.storageLocation.enums.StorageLocationType;

@Schema(description = "Storage location where inventory is held, such as a site store, a central "
        + "warehouse or a godown.")
@Data
public class StorageLocationDto {

    @Schema(description = "Id of the storage location.", example = "18")
    private Long id;

    @Schema(description = "Name of the storage location.", example = "Central Warehouse - Chennai")
    private String locationName;

    @Schema(description = "Type of storage location.", example = "WAREHOUSE")
    private StorageLocationType locationType;

    @Schema(description = "Postal address of the storage location.", example = "Plot 14, Ambattur Industrial Estate, Chennai")
    private String address;

    @Schema(description = "Id of the project this location serves, if any.", example = "12")
    private Long projectId;

    @Schema(description = "Name of the project this location serves, if any.", example = "Asset Homes - Site B")
    private String projectName;

    @Schema(description = "Storage capacity, free text since units vary by material.", example = "5000 sq ft")
    private String capacity;

    @Schema(description = "Whether the storage location is currently active.", example = "true")
    private boolean isActive;

    @Schema(description = "Count of distinct items currently stocked at this location.", example = "42")
    private Long storageItemsCount;

    @Schema(description = "Latitude of the storage location.", example = "13.0827")
    private Float latitude;

    @Schema(description = "Longitude of the storage location.", example = "80.2707")
    private Float longitude;
}
