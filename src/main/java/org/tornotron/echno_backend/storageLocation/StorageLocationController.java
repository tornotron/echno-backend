package org.tornotron.echno_backend.storageLocation;

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
public class StorageLocationController {

    private final StorageLocationService storageLocationService;

    public StorageLocationController(StorageLocationService storageLocationService) {
        this.storageLocationService = storageLocationService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('storage-location:admin')")
    public ResponseEntity<StorageLocationDto> createStorageLocation(
            @Valid @RequestBody StorageLocationCreationDto creationDto) {
        StorageLocationDto storageLocation = storageLocationService.createStorageLocation(creationDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(storageLocation);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('storage-location:read') or hasAuthority('storage-location:admin')")
    public ResponseEntity<StorageLocationDto> getStorageLocationById(@PathVariable Long id) {
        StorageLocationDto storageLocation = storageLocationService.getStorageLocationById(id);
        return ResponseEntity.ok(storageLocation);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('storage-location:read') or hasAuthority('storage-location:admin')")
    public ResponseEntity<List<StorageLocationDto>> getAllStorageLocations() {
        List<StorageLocationDto> storageLocations = storageLocationService.getAllStorageLocations();
        return ResponseEntity.ok(storageLocations);
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('storage-location:read') or hasAuthority('storage-location:admin')")
    public ResponseEntity<Page<StorageLocationDto>> getAllStorageLocationsPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize) {
        Page<StorageLocationDto> storageLocations = storageLocationService.getAllStorageLocations(pageNo, pageSize);
        return ResponseEntity.ok(storageLocations);
    }

    @GetMapping("/project/{projectId}")
    @PreAuthorize("hasAuthority('storage-location:read') or hasAuthority('storage-location:admin')")
    public ResponseEntity<List<StorageLocationDto>> getStorageLocationsByProject(@PathVariable Long projectId) {
        List<StorageLocationDto> storageLocations = storageLocationService.getStorageLocationsByProject(projectId);
        return ResponseEntity.ok(storageLocations);
    }

    @GetMapping("/type/{locationType}")
    @PreAuthorize("hasAuthority('storage-location:read') or hasAuthority('storage-location:admin')")
    public ResponseEntity<List<StorageLocationDto>> getStorageLocationsByType(
            @PathVariable StorageLocationType locationType) {
        List<StorageLocationDto> storageLocations = storageLocationService.getStorageLocationsByType(locationType);
        return ResponseEntity.ok(storageLocations);
    }
}
