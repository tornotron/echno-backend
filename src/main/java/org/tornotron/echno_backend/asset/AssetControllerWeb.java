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
import org.tornotron.echno_backend.asset.dto.AssetMovementCreationDto;
import org.tornotron.echno_backend.asset.dto.AssetMovementDto;
import org.tornotron.echno_backend.asset.dto.AssetPlacementSpanDto;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/assets/web")
@Tag(
        name = "Assets",
        description = "Fixed assets in the organization's asset register: excavators, cranes, vehicles "
                + "and similar equipment tracked with purchase, condition, maintenance and insurance "
                + "detail. Endpoints cover listing, paginated listing, lookup by id, creation, update and "
                + "deletion, plus the append-only movement ledger that records where each asset has been "
                + "and the documents filed against it. All scoped to the caller's current tenant "
                + "organization."
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
            description = "Returns at most 500 rows. X-Total-Count carries the true total and X-Result-Capped is set when rows were left out; use the paginated variant for a complete result."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Assets returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither a member of the current tenant nor holds an elevated role in it")
    })
    public ResponseEntity<List<AssetDto>> readAllAssets() {
        return UnpagedResultCap.respond(assetService.getAllAssets(0, UnpagedResultCap.MAX_ROWS));
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

    @PostMapping("/{id}/movements")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Move an asset",
            description = "Appends an entry to the asset's movement ledger and brings the asset's "
                    + "current project, storage location and custodian with it. A reason is required: "
                    + "an entry with no stated reason is what makes a ledger unexplainable. Entries are "
                    + "never edited or deleted, so an entry made in error is put right by sending "
                    + "correctsMovementId, which records this entry as a CORRECTION of that one."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Movement recorded"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation, the movement is dated in the future, or it would move the asset nowhere"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No asset, project, location or corrected entry with the given id")
    })
    public ResponseEntity<AssetMovementDto> recordMovement(@PathVariable Long id,
                                                           @Valid @RequestBody AssetMovementCreationDto creationDto) {
        return new ResponseEntity<>(assetService.recordMovement(id, creationDto), HttpStatus.CREATED);
    }

    @GetMapping("/{id}/movements")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Read an asset's movement ledger",
            description = "Returns a page of the asset's movement entries, newest first: what moved, "
                    + "from where to where, when, by whom and why."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of movements returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither a member of the current tenant nor holds an elevated role in it"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No asset with the given id")
    })
    public ResponseEntity<Page<AssetMovementDto>> readMovements(@PathVariable Long id,
                                                                @RequestParam(defaultValue = "0") int pageNo,
                                                                @RequestParam(defaultValue = "20") int pageSize) {
        return new ResponseEntity<>(assetService.getMovements(id, pageNo, pageSize), HttpStatus.OK);
    }

    @GetMapping("/{id}/placement-history")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Read how long an asset spent in each place",
            description = "Turns consecutive ledger entries into the stretches of time the asset "
                    + "spent in one place, oldest first, with the number of whole days each lasted "
                    + "and the last one left open. Derived from the ledger on every read, so it "
                    + "cannot drift from it. Reads at most the 500 oldest entries; use the movements "
                    + "endpoint for an asset with a longer history than that."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Placement history returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither a member of the current tenant nor holds an elevated role in it"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No asset with the given id")
    })
    public ResponseEntity<List<AssetPlacementSpanDto>> readPlacementHistory(@PathVariable Long id) {
        return new ResponseEntity<>(assetService.getPlacementHistory(id), HttpStatus.OK);
    }

    @GetMapping("/{id}/documents")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "List an asset's documents",
            description = "Returns the files filed against the asset: purchase invoice, warranty, "
                    + "insurance, registration, certifications and service records, each with its "
                    + "document type and expiry where one was recorded. Upload them through the "
                    + "attachment endpoints with entityType ASSET_DOCUMENTS and the asset id, then "
                    + "record the type and expiry with PATCH /api/v1/attachment/web/attachmentId/"
                    + "{attachmentId}/document."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Documents returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither a member of the current tenant nor holds an elevated role in it"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No asset with the given id")
    })
    public ResponseEntity<List<AttachmentDto>> readDocuments(@PathVariable Long id) {
        return new ResponseEntity<>(assetService.getDocuments(id), HttpStatus.OK);
    }

    @GetMapping("/documents/expiring")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "List asset documents that are about to expire",
            description = "Returns every asset document in the organization whose expiry falls on or "
                    + "before the given number of days from today, soonest first. Documents that have "
                    + "already lapsed are included, since those are the ones that most need chasing. "
                    + "Capped at 500 rows."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Expiring documents returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "withinDays was negative"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither a member of the current tenant nor holds an elevated role in it")
    })
    public ResponseEntity<List<AttachmentDto>> readExpiringDocuments(
            @RequestParam(defaultValue = "30") int withinDays) {
        return new ResponseEntity<>(assetService.getExpiringDocuments(withinDays), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Delete an asset",
            description = "Removes the asset with the given id from the register. Refused once the "
                    + "asset carries movement entries, because deleting it would take the record of "
                    + "where the machine has been with it; retire it by setting its status instead."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Asset deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The asset has recorded movements and cannot be deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No asset with the given id")
    })
    public ResponseEntity<ApiResponse> deleteAsset(@PathVariable Long id) {
        assetService.deleteAsset(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Asset with id: " + id + " has been deleted"));
    }
}
