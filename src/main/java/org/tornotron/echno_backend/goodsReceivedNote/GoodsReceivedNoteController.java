package org.tornotron.echno_backend.goodsReceivedNote;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteCreationDto;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteDto;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteUpdateDto;

import java.time.LocalDateTime;
import java.util.List;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;

@RestController
@RequestMapping("/api/v1/grns")
@Validated
@Tag(
        name = "Goods Received Notes",
        description = "Goods received notes (GRNs) record the receipt of materials from a vendor against "
                + "a purchase order. Each GRN carries the vendor, project, storage location, delivery and "
                + "invoice references and a list of received line items with ordered and received "
                + "quantities. Creating a GRN posts the received quantities into stock. Endpoints require "
                + "the matching grn create, read, update or admin authority."
)
public class GoodsReceivedNoteController {

    private final GoodsReceivedNoteService goodsReceivedNoteService;

    public GoodsReceivedNoteController(GoodsReceivedNoteService goodsReceivedNoteService) {
        this.goodsReceivedNoteService = goodsReceivedNoteService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('grn:create') or hasAuthority('grn:admin')")
    @Operation(
            summary = "Create a goods received note",
            description = "Records a goods receipt from a vendor with its line items. The received "
                    + "quantities are posted into stock at the given storage location as GRN transactions."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "GRN created and returned"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the grn create or admin authority"),
            @ApiResponse(responseCode = "404", description = "A referenced vendor, project, storage location or material was not found")
    })
    public ResponseEntity<GoodsReceivedNoteDto> createGrn(@Valid @RequestBody GoodsReceivedNoteCreationDto creationDto) {
        GoodsReceivedNoteDto created = goodsReceivedNoteService.createGoodsReceivedNote(creationDto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('grn:read') or hasAuthority('grn:admin')")
    @Operation(
            summary = "Get a goods received note by id",
            description = "Returns a single GRN with its header details and received line items."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "GRN found"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the grn read or admin authority"),
            @ApiResponse(responseCode = "404", description = "No GRN with the given id")
    })
    public ResponseEntity<GoodsReceivedNoteDto> getGrnById(@PathVariable Long id) {
        GoodsReceivedNoteDto grn = goodsReceivedNoteService.getGrnById(id);
        return ResponseEntity.ok(grn);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('grn:read') or hasAuthority('grn:admin')")
    @Operation(
            summary = "List all goods received notes",
            description = "Returns at most 500 rows. X-Total-Count carries the true total and X-Result-Capped is set when rows were left out; use the paginated variant for a complete result."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of GRNs"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the grn read or admin authority")
    })
    public ResponseEntity<List<GoodsReceivedNoteDto>> getAllGrns() {
        return UnpagedResultCap.respond(
                goodsReceivedNoteService.getAllGrns(0, UnpagedResultCap.MAX_ROWS));
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('grn:read') or hasAuthority('grn:admin')")
    @Operation(
            summary = "List goods received notes (paginated)",
            description = "Returns a page of GRNs. The pageNo and pageSize parameters control the slice "
                    + "returned."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of GRNs"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the grn read or admin authority")
    })
    public ResponseEntity<Page<GoodsReceivedNoteDto>> getAllGrnsPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        Page<GoodsReceivedNoteDto> grns = goodsReceivedNoteService.getAllGrns(pageNo, pageSize);
        return ResponseEntity.ok(grns);
    }

    @GetMapping("/vendor/{vendorId}")
    @PreAuthorize("hasAuthority('grn:read') or hasAuthority('grn:admin')")
    @Operation(
            summary = "List goods received notes for a vendor",
            description = "Returns every GRN recorded against the given vendor."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of GRNs for the vendor"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the grn read or admin authority")
    })
    public ResponseEntity<List<GoodsReceivedNoteDto>> getGrnsByVendor(@PathVariable Long vendorId) {
        List<GoodsReceivedNoteDto> grns = goodsReceivedNoteService.getGrnsByVendor(vendorId);
        return ResponseEntity.ok(grns);
    }

    @PatchMapping
    @PreAuthorize("hasAuthority('grn:update') or hasAuthority('grn:admin')")
    @Operation(
            summary = "Update a goods received note",
            description = "Updates the editable header fields of an existing GRN (receipt date, receiver, "
                    + "delivery challan, invoice details and storage location). The GRN to update is "
                    + "identified by the id in the request body. Line items are not changed here."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "GRN updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the grn update or admin authority"),
            @ApiResponse(responseCode = "404", description = "No GRN with the given id")
    })
    public ResponseEntity<GoodsReceivedNoteDto> updateGrn(@Valid @RequestBody GoodsReceivedNoteUpdateDto updateDto) {
        GoodsReceivedNoteDto updated = goodsReceivedNoteService.updateGoodsReceivedNote(updateDto);
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/date-range")
    @PreAuthorize("hasAuthority('grn:read') or hasAuthority('grn:admin')")
    @Operation(
            summary = "List goods received notes in a date range",
            description = "Returns GRNs whose received date falls between the startDate and endDate "
                    + "parameters (both ISO date-time values)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of GRNs in the range"),
            @ApiResponse(responseCode = "400", description = "startDate or endDate is missing or not a valid ISO date-time"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the grn read or admin authority")
    })
    public ResponseEntity<List<GoodsReceivedNoteDto>> getGrnsByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate
    ) {
        List<GoodsReceivedNoteDto> grns = goodsReceivedNoteService.getGrnsByDateRange(startDate, endDate);
        return ResponseEntity.ok(grns);
    }
}
