package org.tornotron.echno_backend.storageLocation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.storageLocation.dto.StorageLocationCreationDto;
import org.tornotron.echno_backend.storageLocation.dto.StorageLocationDto;
import org.tornotron.echno_backend.storageLocation.enums.StorageLocationType;

import java.util.List;

@RestController
@RequestMapping("/api/v1/storage-locations")
@Validated
@Tag(
        name = "Storage Locations",
        description = "Physical places where inventory is held, such as a site store, a central warehouse "
                + "or a godown. A location carries a name, type, optional project link and coordinates. "
                + "Endpoints cover creating a location and listing or looking one up by id, project or "
                + "type. Access is gated by the storage-location authorities, with an admin authority "
                + "that grants all operations."
)
public class StorageLocationController {

    private final StorageLocationService storageLocationService;

    public StorageLocationController(StorageLocationService storageLocationService) {
        this.storageLocationService = storageLocationService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('storage-location:admin')")
    @Operation(
            summary = "Create a storage location",
            description = "Creates a storage location, such as \"Central Warehouse - Chennai\" or "
                    + "\"Site B Yard\". The project link is optional since a central warehouse or godown "
                    + "can serve more than one project."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Storage location created"),
            @ApiResponse(responseCode = "400", description = "A field failed validation"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the storage-location admin authority"),
            @ApiResponse(responseCode = "409", description = "A storage location with the same name already exists in the organization")
    })
    public ResponseEntity<StorageLocationDto> createStorageLocation(
            @Valid @RequestBody StorageLocationCreationDto creationDto) {
        StorageLocationDto storageLocation = storageLocationService.createStorageLocation(creationDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(storageLocation);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('storage-location:read') or hasAuthority('storage-location:admin')")
    @Operation(
            summary = "Get a storage location by id",
            description = "Returns a single storage location, including its resolved project name and "
                    + "current stored-items count."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Storage location found"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the storage-location read or admin authority"),
            @ApiResponse(responseCode = "404", description = "No storage location with the given id")
    })
    public ResponseEntity<StorageLocationDto> getStorageLocationById(@PathVariable Long id) {
        StorageLocationDto storageLocation = storageLocationService.getStorageLocationById(id);
        return ResponseEntity.ok(storageLocation);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('storage-location:read') or hasAuthority('storage-location:admin')")
    @Operation(
            summary = "List all storage locations",
            description = "Returns every storage location in the caller's organization, unpaginated."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Storage locations returned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the storage-location read or admin authority")
    })
    public ResponseEntity<List<StorageLocationDto>> getAllStorageLocations() {
        List<StorageLocationDto> storageLocations = storageLocationService.getAllStorageLocations();
        return ResponseEntity.ok(storageLocations);
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('storage-location:read') or hasAuthority('storage-location:admin')")
    @Operation(
            summary = "List storage locations, paginated",
            description = "Returns a single page of storage locations. The pageNo and pageSize "
                    + "parameters control paging."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of storage locations returned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the storage-location read or admin authority")
    })
    public ResponseEntity<Page<StorageLocationDto>> getAllStorageLocationsPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize) {
        Page<StorageLocationDto> storageLocations = storageLocationService.getAllStorageLocations(pageNo, pageSize);
        return ResponseEntity.ok(storageLocations);
    }

    @GetMapping("/project/{projectId}")
    @PreAuthorize("hasAuthority('storage-location:read') or hasAuthority('storage-location:admin')")
    @Operation(
            summary = "List storage locations for a project",
            description = "Returns the storage locations linked to the given project id, for example the "
                    + "site stores serving a particular construction site."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Storage locations returned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the storage-location read or admin authority")
    })
    public ResponseEntity<List<StorageLocationDto>> getStorageLocationsByProject(@PathVariable Long projectId) {
        List<StorageLocationDto> storageLocations = storageLocationService.getStorageLocationsByProject(projectId);
        return ResponseEntity.ok(storageLocations);
    }

    @GetMapping("/type/{locationType}")
    @PreAuthorize("hasAuthority('storage-location:read') or hasAuthority('storage-location:admin')")
    @Operation(
            summary = "List storage locations by type",
            description = "Returns the storage locations of the given type, for example all WAREHOUSE "
                    + "locations across the organization."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Storage locations returned"),
            @ApiResponse(responseCode = "400", description = "locationType is not a recognized storage location type"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the storage-location read or admin authority")
    })
    public ResponseEntity<List<StorageLocationDto>> getStorageLocationsByType(
            @PathVariable StorageLocationType locationType) {
        List<StorageLocationDto> storageLocations = storageLocationService.getStorageLocationsByType(locationType);
        return ResponseEntity.ok(storageLocations);
    }
}
