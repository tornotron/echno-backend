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
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferCancellationDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferCreationDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferReceiptDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferDto;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;

import java.util.List;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;

@RestController
@RequestMapping("/api/v1/site-transfers")
@Validated
@Tag(
        name = "Site Transfers",
        description = "Movement of materials between two projects, and optionally between specific storage "
                + "locations within them. A site transfer records the sending person, the items and "
                + "quantities being moved, and its own status as it progresses. Creating a transfer checks "
                + "that the sending location holds enough stock before the items are recorded. Access is "
                + "gated by the site-transfer authorities, with an admin authority that grants all operations."
)
public class SiteTransferController {

    private final SiteTransferService siteTransferService;

    public SiteTransferController(SiteTransferService siteTransferService) {
        this.siteTransferService = siteTransferService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('site-transfer:create') or hasAuthority('site-transfer:admin')")
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the site-transfer create or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "The sending person, sending project, receiving project, a referenced storage location, or a material on one of the items was not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "A site transfer with the given transfer number already exists, or the sending location does not hold enough stock for one or more items")
    })
    public ResponseEntity<SiteTransferDto> createSiteTransfer(@Valid @RequestBody SiteTransferCreationDto creationDto) {
        SiteTransferDto created = siteTransferService.createSiteTransfer(creationDto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('site-transfer:read') or hasAuthority('site-transfer:admin')")
    @Operation(
            summary = "Get a site transfer by id",
            description = "Returns a single site transfer including its sending and receiving projects, "
                    + "storage locations, sending person, and line items."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Site transfer found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the site-transfer read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No site transfer with the given id")
    })
    public ResponseEntity<SiteTransferDto> getSiteTransferById(@PathVariable Long id) {
        SiteTransferDto transfer = siteTransferService.getSiteTransferById(id);
        return ResponseEntity.ok(transfer);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('site-transfer:read') or hasAuthority('site-transfer:admin')")
    @Operation(
            summary = "List all site transfers",
            description = "Returns at most 500 rows. X-Total-Count carries the true total and X-Result-Capped is set when rows were left out; use the paginated variant for a complete result."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Site transfers returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the site-transfer read or admin authority")
    })
    public ResponseEntity<List<SiteTransferDto>> getAllSiteTransfers() {
        return UnpagedResultCap.respond(
                siteTransferService.getAllSiteTransfers(0, UnpagedResultCap.MAX_ROWS));
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('site-transfer:read') or hasAuthority('site-transfer:admin')")
    @Operation(
            summary = "List site transfers, paginated",
            description = "Returns a single page of site transfers ordered by issue date, most recent first. "
                    + "The pageNo and pageSize parameters control paging."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of site transfers returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the site-transfer read or admin authority")
    })
    public ResponseEntity<Page<SiteTransferDto>> getAllSiteTransfersPaginated(
            @Valid @ParameterObject PageQuery pageQuery
    ) {
        Page<SiteTransferDto> transfers = siteTransferService.getAllSiteTransfers(pageQuery.getPageNo(), pageQuery.getPageSize());
        return ResponseEntity.ok(transfers);
    }

    @GetMapping("/status/{status}")
    @PreAuthorize("hasAuthority('site-transfer:read') or hasAuthority('site-transfer:admin')")
    @Operation(
            summary = "List site transfers by status",
            description = "Returns every site transfer currently in the given status, for example PENDING, "
                    + "IN_TRANSIT or COMPLETED."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Site transfers returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the site-transfer read or admin authority")
    })
    public ResponseEntity<List<SiteTransferDto>> getSiteTransfersByStatus(@PathVariable SiteTransferStatus status) {
        List<SiteTransferDto> transfers = siteTransferService.getSiteTransfersByStatus(status);
        return ResponseEntity.ok(transfers);
    }

    @GetMapping("/sending-project/{projectId}")
    @PreAuthorize("hasAuthority('site-transfer:read') or hasAuthority('site-transfer:admin')")
    @Operation(
            summary = "List site transfers sent from a project",
            description = "Returns every site transfer whose sending project is the given project id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Site transfers returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the site-transfer read or admin authority")
    })
    public ResponseEntity<List<SiteTransferDto>> getSiteTransfersBySendingProject(@PathVariable Long projectId) {
        List<SiteTransferDto> transfers = siteTransferService.getSiteTransfersBySendingProject(projectId);
        return ResponseEntity.ok(transfers);
    }

    @GetMapping("/receiving-project/{projectId}")
    @PreAuthorize("hasAuthority('site-transfer:read') or hasAuthority('site-transfer:admin')")
    @Operation(
            summary = "List site transfers received by a project",
            description = "Returns every site transfer whose receiving project is the given project id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Site transfers returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the site-transfer read or admin authority")
    })
    public ResponseEntity<List<SiteTransferDto>> getSiteTransfersByReceivingProject(@PathVariable Long projectId) {
        List<SiteTransferDto> transfers = siteTransferService.getSiteTransfersByReceivingProject(projectId);
        return ResponseEntity.ok(transfers);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('site-transfer:update') or hasAuthority('site-transfer:admin')")
    @Deprecated
    @Operation(
            deprecated = true,
            summary = "Deprecated: a transfer's status can no longer be set from a payload",
            description = "Always refuses, and names the endpoint that does what the caller "
                    + "wants. This used to assign whatever status it was handed and move no stock, "
                    + "which is how a transfer could read COMPLETED with nothing confirmed. Every "
                    + "state a transfer can hold now follows from a movement: record a delivery "
                    + "with POST /{id}/receive, or abandon one that never arrived with POST "
                    + "/{id}/cancel. The route is kept so an existing client gets an answer that "
                    + "names its replacement rather than a 404."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Always: the status is derived from movements and cannot be set"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the site-transfer update or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No site transfer with the given id")
    })
    public ResponseEntity<ApiResponse> updateSiteTransferStatus(
            @PathVariable Long id,
            @RequestParam SiteTransferStatus status
    ) {
        siteTransferService.updateSiteTransferStatus(id, status);
        return ResponseEntity.ok(new ApiResponse("Site transfer status updated successfully"));
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAuthority('site-transfer:update') or hasAuthority('site-transfer:admin')")
    @Operation(
            summary = "Record what the receiving site took delivery of",
            description = "Posts the stock that actually arrived at the receiving project and "
                    + "location, writes each line's received quantity, and works the transfer's "
                    + "status out from the arithmetic: everything received is COMPLETED, some "
                    + "received is PARTIALLY_TRANSFERRED, nothing received leaves it PENDING. Who "
                    + "confirmed the delivery is taken from the session, never from the payload, so "
                    + "the receipt is that person's own statement. Receiving less than was sent is "
                    + "accepted and leaves an open variance on the transfer, visible as each line's "
                    + "in-transit quantity and closed by a stock adjustment naming the transfer; the "
                    + "transfer writes no loss movement of its own, because that would be a stock "
                    + "correction nobody authorised. Receiving more than was sent is refused unless "
                    + "the payload sets allowOverReceipt. Only a transfer that crosses a project "
                    + "boundary can be received: one between two stores on a single project had both "
                    + "of its legs written at creation, because the material never left that site's "
                    + "custody."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Receipt recorded and the stock that arrived posted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The transfer never left one project, or it is already completed or cancelled, or a named line is not on it, or a line would be over-received without allowOverReceipt"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the site-transfer update or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No site transfer with the given id")
    })
    public ResponseEntity<SiteTransferDto> receiveSiteTransfer(
            @PathVariable Long id,
            @Valid @RequestBody SiteTransferReceiptDto receiptDto
    ) {
        return ResponseEntity.ok(siteTransferService.receiveSiteTransfer(id, receiptDto));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('site-transfer:update') or hasAuthority('site-transfer:admin')")
    @Operation(
            summary = "Cancel a transfer that never arrived",
            description = "Abandons a PENDING transfer and returns the whole sent quantity to the "
                    + "sending project and location it was drawn from. Without the reversal a "
                    + "transfer written off in transit would leave the sending project permanently "
                    + "short with no way back. Only a PENDING transfer can be cancelled: once "
                    + "something has been received, part of the material is standing at the far "
                    + "site and what to do about the rest is a decision for a stock adjustment "
                    + "rather than a reversal."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Transfer cancelled and its outbound leg reversed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The transfer is in any state but PENDING, or no reason was given"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the site-transfer update or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No site transfer with the given id")
    })
    public ResponseEntity<SiteTransferDto> cancelSiteTransfer(
            @PathVariable Long id,
            @Valid @RequestBody SiteTransferCancellationDto cancellationDto
    ) {
        return ResponseEntity.ok(siteTransferService.cancelSiteTransfer(id, cancellationDto));
    }
}
