package org.tornotron.echno_backend.goodsReceivedNote;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteCreationDto;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteDto;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteUpdateDto;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/grns/web")
@Validated
@Tag(
        name = "Goods Received Notes (Web)",
        description = "Goods received notes (GRNs) record materials received against a vendor, capturing "
                + "the delivery and its line items. This is the web-console API, restricted to the "
                + "system-admin role for the current tenant rather than flat authorities. Endpoints "
                + "cover creating, browsing, filtering by vendor or date range, and updating GRNs."
)
public class GoodsReceivedNoteControllerWeb {

    private final GoodsReceivedNoteService goodsReceivedNoteService;

    public GoodsReceivedNoteControllerWeb(GoodsReceivedNoteService goodsReceivedNoteService) {
        this.goodsReceivedNoteService = goodsReceivedNoteService;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Create a goods received note",
            description = "Creates a GRN recording materials received against a vendor with its line items."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Goods received note created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<GoodsReceivedNoteDto> createGrn(@Valid @RequestBody GoodsReceivedNoteCreationDto creationDto) {
        GoodsReceivedNoteDto created = goodsReceivedNoteService.createGoodsReceivedNote(creationDto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Get a goods received note by id",
            description = "Returns a single GRN."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Goods received note found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No goods received note with the given id")
    })
    public ResponseEntity<GoodsReceivedNoteDto> getGrnById(@PathVariable Long id) {
        GoodsReceivedNoteDto grn = goodsReceivedNoteService.getGrnById(id);
        return ResponseEntity.ok(grn);
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List goods received notes",
            description = "Returns at most 500 rows. X-Total-Count carries the true total and X-Result-Capped is set when rows were left out; use the paginated variant for a complete result."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Goods received notes returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<GoodsReceivedNoteDto>> getAllGrns() {
        return UnpagedResultCap.respond(goodsReceivedNoteService.getAllGrns(0, UnpagedResultCap.MAX_ROWS));
    }

    @GetMapping("/all")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List goods received notes (paged)",
            description = "Returns a single page of GRNs controlled by the pageNo and pageSize parameters."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of goods received notes returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<Page<GoodsReceivedNoteDto>> getAllGrnsPaginated(
            @Valid @ParameterObject PageQuery pageQuery
    ) {
        Page<GoodsReceivedNoteDto> grns = goodsReceivedNoteService.getAllGrns(pageQuery.getPageNo(), pageQuery.getPageSize());
        return ResponseEntity.ok(grns);
    }

    @GetMapping("/vendor/{vendorId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List goods received notes for a vendor",
            description = "Returns the GRNs recorded against the given vendor."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Goods received notes returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<GoodsReceivedNoteDto>> getGrnsByVendor(@PathVariable Long vendorId) {
        List<GoodsReceivedNoteDto> grns = goodsReceivedNoteService.getGrnsByVendor(vendorId);
        return ResponseEntity.ok(grns);
    }

    @PatchMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Update a goods received note",
            description = "Applies an update to the GRN identified in the request body."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Goods received note updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No goods received note with the given id")
    })
    public ResponseEntity<GoodsReceivedNoteDto> updateGrn(@Valid @RequestBody GoodsReceivedNoteUpdateDto updateDto) {
        GoodsReceivedNoteDto updated = goodsReceivedNoteService.updateGoodsReceivedNote(updateDto);
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/date-range")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List goods received notes by date range",
            description = "Returns the GRNs recorded between the given start and end date-times."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Goods received notes returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A date parameter is missing or malformed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<GoodsReceivedNoteDto>> getGrnsByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate
    ) {
        List<GoodsReceivedNoteDto> grns = goodsReceivedNoteService.getGrnsByDateRange(startDate, endDate);
        return ResponseEntity.ok(grns);
    }

}
