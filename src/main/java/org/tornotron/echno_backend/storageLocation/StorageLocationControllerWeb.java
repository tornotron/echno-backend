package org.tornotron.echno_backend.storageLocation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.storageLocation.dto.StorageLocationCreationDto;
import org.tornotron.echno_backend.storageLocation.dto.StorageLocationDto;
import org.tornotron.echno_backend.storageLocation.dto.StorageLocationUpdateDto;
import org.tornotron.echno_backend.storageLocation.enums.StorageLocationType;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;

import java.util.List;

@RestController
@RequestMapping("/api/v1/storage-locations/web")
@Validated
@Tag(
        name = "Storage Locations (Web)",
        description = "Web-console counterpart of the storage location API, gated by organization role "
                + "instead of a flat authority. Covers the same create, read and lookup operations as the "
                + "mobile API plus partial update and delete, which the mobile API does not expose. Every "
                + "operation requires the system-admin role in the caller's current tenant."
)
public class StorageLocationControllerWeb {

    private final StorageLocationService storageLocationService;

    public StorageLocationControllerWeb(StorageLocationService storageLocationService) {
        this.storageLocationService = storageLocationService;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Create a storage location",
            description = "Creates a storage location, such as \"Central Warehouse - Chennai\" or "
                    + "\"Site B Yard\". The project link is optional since a central warehouse or godown "
                    + "can serve more than one project."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Storage location created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "A storage location with the same name already exists in the organization")
    })
    public ResponseEntity<StorageLocationDto> createStorageLocation(
            @Valid @RequestBody StorageLocationCreationDto creationDto) {
        StorageLocationDto storageLocation = storageLocationService.createStorageLocation(creationDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(storageLocation);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Get a storage location by id",
            description = "Returns a single storage location, including its resolved project name and "
                    + "current stored-items count."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Storage location found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No storage location with the given id")
    })
    public ResponseEntity<StorageLocationDto> getStorageLocationById(@PathVariable Long id) {
        StorageLocationDto storageLocation = storageLocationService.getStorageLocationById(id);
        return ResponseEntity.ok(storageLocation);
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List all storage locations",
            description = "Returns at most 500 rows. X-Total-Count carries the true total and X-Result-Capped is set when rows were left out; use the paginated variant for a complete result."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Storage locations returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<StorageLocationDto>> getAllStorageLocations() {
        return UnpagedResultCap.respond(
                storageLocationService.getAllStorageLocations(0, UnpagedResultCap.MAX_ROWS));
    }

    @GetMapping("/all")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List storage locations, paginated",
            description = "Returns a single page of storage locations. The pageNo and pageSize "
                    + "parameters control paging."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of storage locations returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<Page<StorageLocationDto>> getAllStorageLocationsPaginated(
            @Valid @ParameterObject PageQuery pageQuery) {
        Page<StorageLocationDto> storageLocations = storageLocationService.getAllStorageLocations(pageQuery.getPageNo(), pageQuery.getPageSize());
        return ResponseEntity.ok(storageLocations);
    }

    @GetMapping("/project/{projectId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List storage locations for a project",
            description = "Returns the storage locations linked to the given project id, for example the "
                    + "site stores serving a particular construction site."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Storage locations returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<StorageLocationDto>> getStorageLocationsByProject(@PathVariable Long projectId) {
        List<StorageLocationDto> storageLocations = storageLocationService.getStorageLocationsByProject(projectId);
        return ResponseEntity.ok(storageLocations);
    }

    @GetMapping("/type/{locationType}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List storage locations by type",
            description = "Returns the storage locations of the given type, for example all WAREHOUSE "
                    + "locations across the organization."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Storage locations returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "locationType is not a recognized storage location type"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<StorageLocationDto>> getStorageLocationsByType(
            @PathVariable StorageLocationType locationType) {
        List<StorageLocationDto> storageLocations = storageLocationService.getStorageLocationsByType(locationType);
        return ResponseEntity.ok(storageLocations);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Update a storage location",
            description = "Applies a partial update to a storage location. Only the fields present in the "
                    + "request body are changed; a location name change is checked against other "
                    + "locations in the organization."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Storage location updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No storage location with the given id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Another storage location already uses the requested name")
    })
    public ResponseEntity<StorageLocationDto> updateStorageLocation(
            @PathVariable Long id,
            @Valid @RequestBody StorageLocationUpdateDto updateDto) {
        StorageLocationDto storageLocation = storageLocationService.updateStorageLocation(id, updateDto);
        return ResponseEntity.ok(storageLocation);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Delete a storage location",
            description = "Deletes the storage location with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Storage location deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No storage location with the given id")
    })
    public ResponseEntity<ApiResponse> deleteStorageLocation(@PathVariable Long id) {
        storageLocationService.deleteStorageLocation(id);
        return ResponseEntity.ok(new ApiResponse("Storage location with id: " + id + " deleted successfully"));
    }
}
