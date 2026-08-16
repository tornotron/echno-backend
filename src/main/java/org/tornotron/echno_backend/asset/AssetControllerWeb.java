package org.tornotron.echno_backend.asset;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.asset.dto.AssetCreationDto;
import org.tornotron.echno_backend.asset.dto.AssetDto;
import org.tornotron.echno_backend.common.response.ApiResponse;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/assets/web")
public class AssetControllerWeb {

    private final AssetService assetService;

    public AssetControllerWeb(AssetService assetService) {
        this.assetService = assetService;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<List<AssetDto>> readAllAssets() {
        return new ResponseEntity<>(assetService.getAllAssets(), HttpStatus.OK);
    }

    @GetMapping("/paginated")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<Page<AssetDto>> readAllAssetsPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize) {
        return new ResponseEntity<>(assetService.getAllAssets(pageNo, pageSize), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<AssetDto> readAnAsset(@PathVariable Long id) {
        return new ResponseEntity<>(assetService.getAssetById(id), HttpStatus.OK);
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    public ResponseEntity<AssetDto> createAsset(@Valid @RequestBody AssetCreationDto creationDto) {
        return new ResponseEntity<>(assetService.createAsset(creationDto), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    public ResponseEntity<AssetDto> updateAsset(@PathVariable Long id, @Valid @RequestBody AssetCreationDto creationDto) {
        return new ResponseEntity<>(assetService.updateAsset(id, creationDto), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    public ResponseEntity<ApiResponse> deleteAsset(@PathVariable Long id) {
        assetService.deleteAsset(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Asset with id: " + id + " has been deleted"));
    }
}
