package org.tornotron.echno_backend.siteTransfer;

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
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferCreationDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferDto;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;

import java.util.List;

@RestController
@RequestMapping("/api/v1/site-transfers/web")
@Validated
@Tag(
        name = "Site Transfers (Web)",
        description = "Web-app view of site transfers: the same movement of materials between two projects "
                + "and their storage locations as the mobile-facing site-transfer endpoints, restricted to "
                + "the system-admin role for the caller's current tenant. Creating a transfer checks that "
                + "the sending location holds enough stock before the items are recorded."
)
public class SiteTransferControllerWeb {

    private final SiteTransferService siteTransferService;

    public SiteTransferControllerWeb(SiteTransferService siteTransferService) {
        this.siteTransferService = siteTransferService;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Create a site transfer",
            description = "Creates a site transfer moving materials from a sending project, and optionally a "
                    + "specific storage location within it, to a receiving project. Validates that the "
                    + "sending location holds enough stock for every item before the transfer is recorded, "
                    + "then publishes an event so inventory is updated automatically."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Site transfer created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A required field is missing, or an item failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "The sending person, sending project, receiving project, a referenced storage location, or a material on one of the items was not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "A site transfer with the given transfer number already exists, or the sending location does not hold enough stock for one or more items")
    })
    public ResponseEntity<SiteTransferDto> createSiteTransfer(@Valid @RequestBody SiteTransferCreationDto creationDto) {
        SiteTransferDto created = siteTransferService.createSiteTransfer(creationDto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Get a site transfer by id",
            description = "Returns a single site transfer including its sending and receiving projects, "
                    + "storage locations, sending person, and line items."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Site transfer found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No site transfer with the given id")
    })
    public ResponseEntity<SiteTransferDto> getSiteTransferById(@PathVariable Long id) {
        SiteTransferDto transfer = siteTransferService.getSiteTransferById(id);
        return ResponseEntity.ok(transfer);
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List all site transfers",
            description = "Returns at most 500 rows. X-Total-Count carries the true total and X-Result-Capped is set when rows were left out; use the paginated variant for a complete result."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Site transfers returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<SiteTransferDto>> getAllSiteTransfers() {
        return UnpagedResultCap.respond(
                siteTransferService.getAllSiteTransfers(0, UnpagedResultCap.MAX_ROWS));
    }

    @GetMapping("/all")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List site transfers, paginated",
            description = "Returns a single page of site transfers ordered by issue date, most recent first. "
                    + "The pageNo and pageSize parameters control paging."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of site transfers returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<Page<SiteTransferDto>> getAllSiteTransfersPaginated(
            @Valid @ParameterObject PageQuery pageQuery
    ) {
        Page<SiteTransferDto> transfers = siteTransferService.getAllSiteTransfers(pageQuery.getPageNo(), pageQuery.getPageSize());
        return ResponseEntity.ok(transfers);
    }

    @GetMapping("/status/{status}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List site transfers by status",
            description = "Returns every site transfer currently in the given status, for example PENDING, "
                    + "IN_TRANSIT or COMPLETED."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Site transfers returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<SiteTransferDto>> getSiteTransfersByStatus(@PathVariable SiteTransferStatus status) {
        List<SiteTransferDto> transfers = siteTransferService.getSiteTransfersByStatus(status);
        return ResponseEntity.ok(transfers);
    }

    @GetMapping("/sending-project/{projectId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List site transfers sent from a project",
            description = "Returns every site transfer whose sending project is the given project id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Site transfers returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<SiteTransferDto>> getSiteTransfersBySendingProject(@PathVariable Long projectId) {
        List<SiteTransferDto> transfers = siteTransferService.getSiteTransfersBySendingProject(projectId);
        return ResponseEntity.ok(transfers);
    }

    @GetMapping("/receiving-project/{projectId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List site transfers received by a project",
            description = "Returns every site transfer whose receiving project is the given project id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Site transfers returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<SiteTransferDto>> getSiteTransfersByReceivingProject(@PathVariable Long projectId) {
        List<SiteTransferDto> transfers = siteTransferService.getSiteTransfersByReceivingProject(projectId);
        return ResponseEntity.ok(transfers);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Update a site transfer's status",
            description = "Sets the status of an existing site transfer, for example moving it from PENDING "
                    + "to IN_TRANSIT or COMPLETED."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Site transfer status updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No site transfer with the given id")
    })
    public ResponseEntity<ApiResponse> updateSiteTransferStatus(
            @PathVariable Long id,
            @RequestParam SiteTransferStatus status
    ) {
        siteTransferService.updateSiteTransferStatus(id, status);
        return ResponseEntity.ok(new ApiResponse("Site transfer status updated successfully"));
    }
}
