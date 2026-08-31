package org.tornotron.echno_backend.storageLocation.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
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

    /**
     * Wrapper rather than a primitive, so "not sent" is distinguishable from "sent as false".
     * As a primitive it defaulted to false and the service applied it unconditionally, which
     * turned every create that omitted the key into an inactive location and defeated the
     * entity's own default of true.
     *
     * <p>The wrapper also settles the wire name. Lombok names a primitive's accessors
     * {@code isActive()}/{@code setActive()}, which Jackson publishes as {@code active}, while a
     * wrapper gets {@code getIsActive()}/{@code setIsActive()} and publishes {@code isActive}.
     * The update payload has always been the wrapper, so create said {@code active} and update
     * said {@code isActive} for the same field. Both say {@code isActive} now.
     */
    @Schema(description = "Whether the storage location is currently active. Optional; a location "
            + "created without it is active. Also accepted under the older name \"active\".",
            example = "true")
    // Transitional shim for echno-core#57: the deployed client sends this key as "active".
    // Remove once a core release sends isActive and echno-web has moved to it.
    @JsonAlias("active")
    private Boolean isActive;

    @Schema(description = "Id of the project this location serves. Optional, a central warehouse or "
            + "godown may serve more than one project.", example = "12")
    private Long projectId;

    @Schema(description = "Latitude of the storage location.", example = "13.0827")
    private Float latitude;

    @Schema(description = "Longitude of the storage location.", example = "80.2707")
    private Float longitude;
}
