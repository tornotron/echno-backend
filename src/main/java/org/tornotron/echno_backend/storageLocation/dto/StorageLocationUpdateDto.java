package org.tornotron.echno_backend.storageLocation.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
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

    /**
     * Lombok names this wrapper's accessors {@code getIsActive()}/{@code setIsActive()}, so
     * Jackson binds it from {@code isActive}. The client has been sending {@code active}, the
     * name the create payload used to publish, so every attempt to deactivate a location bound
     * to nothing and was silently dropped. The alias is what makes those requests land.
     */
    @Schema(description = "New active status for the storage location. Also accepted under the "
            + "older name \"active\".", example = "true")
    // Transitional shim for echno-core#57: the deployed client sends this key as "active".
    // Remove once a core release sends isActive and echno-web has moved to it.
    @JsonAlias("active")
    private Boolean isActive;

    @Schema(description = "New latitude of the storage location.", example = "13.0827")
    private Float latitude;

    @Schema(description = "New longitude of the storage location.", example = "80.2707")
    private Float longitude;

    @Schema(description = "New project id this location serves.", example = "12")
    private Long projectId;
}
