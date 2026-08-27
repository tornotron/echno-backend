package org.tornotron.echno_backend.asset;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(
        name = "Assets",
        description = "Fixed assets in the organization's asset register: excavators, cranes, vehicles "
                + "and similar equipment tracked with purchase, condition, maintenance and insurance "
                + "detail. Endpoints cover listing, paginated listing, lookup by id, creation, update and "
                + "deletion, scoped to the caller's current tenant organization."
)
public class AssetControllerWeb {

    private final AssetService assetService;

    public AssetControllerWeb(AssetService assetService) {
        this.assetService = assetService;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "List all assets",
            description = "Returns every asset in the caller's current tenant organization, unpaginated."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Assets returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither a member of the current tenant nor holds an elevated role in it")
    })
    public ResponseEntity<List<AssetDto>> readAllAssets() {
        return new ResponseEntity<>(assetService.getAllAssets(), HttpStatus.OK);
    }

    @GetMapping("/paginated")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "List assets, paginated",
            description = "Returns a page of assets for the caller's current tenant organization. The "
                    + "pageNo and pageSize parameters control paging."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of assets returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither a member of the current tenant nor holds an elevated role in it")
    })
    public ResponseEntity<Page<AssetDto>> readAllAssetsPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize) {
        return new ResponseEntity<>(assetService.getAllAssets(pageNo, pageSize), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Get an asset by id",
            description = "Returns a single asset, including its resolved vendor and storage location."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Asset found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither a member of the current tenant nor holds an elevated role in it"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No asset with the given id")
    })
    public ResponseEntity<AssetDto> readAnAsset(@PathVariable Long id) {
        return new ResponseEntity<>(assetService.getAssetById(id), HttpStatus.OK);
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Create an asset",
            description = "Adds a new asset to the current tenant organization's asset register, for "
                    + "example a piece of heavy equipment such as a backhoe loader or a tower crane."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Asset created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<AssetDto> createAsset(@Valid @RequestBody AssetCreationDto creationDto) {
        return new ResponseEntity<>(assetService.createAsset(creationDto), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Update an asset",
            description = "Replaces the details of an existing asset with the given values."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Asset updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No asset with the given id")
    })
    public ResponseEntity<AssetDto> updateAsset(@PathVariable Long id, @Valid @RequestBody AssetCreationDto creationDto) {
        return new ResponseEntity<>(assetService.updateAsset(id, creationDto), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Delete an asset",
            description = "Removes the asset with the given id from the register."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Asset deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No asset with the given id")
    })
    public ResponseEntity<ApiResponse> deleteAsset(@PathVariable Long id) {
        assetService.deleteAsset(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Asset with id: " + id + " has been deleted"));
    }
}
