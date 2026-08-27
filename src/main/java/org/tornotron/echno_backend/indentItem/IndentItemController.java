package org.tornotron.echno_backend.indentItem;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.indentItem.dto.IndentItemCreationDto;
import org.tornotron.echno_backend.indentItem.dto.IndentItemDto;

import java.util.List;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;

@RestController
@RequestMapping("/api/v1/indent-items")
@Validated
@Tag(
        name = "Indent Items",
        description = "Requested material items belonging to an indent: material, quantity requested and "
                + "ordered, and whether the item has been converted into a purchase order line. Items can "
                + "be looked up by indent, by material, or by conversion status. Access is gated by the "
                + "indent-item authorities, with an admin authority that grants all operations."
)
public class IndentItemController {

    private final IndentItemService indentItemService;

    public IndentItemController(IndentItemService indentItemService) {
        this.indentItemService = indentItemService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('indent-item:create') or hasAuthority('indent-item:admin')")
    @Operation(
            summary = "Create an indent item",
            description = "Adds a requested material item to an indent, independently of the indent create call."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Indent item created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A required field is missing or failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the indent-item create or admin authority")
    })
    public ResponseEntity<IndentItemDto> createIndentItem(@Valid @RequestBody IndentItemCreationDto creationDto) {
        IndentItemDto created = indentItemService.createIndentItem(creationDto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('indent-item:read') or hasAuthority('indent-item:admin')")
    @Operation(
            summary = "Get an indent item by id",
            description = "Returns a single indent item, including its material and conversion status."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Indent item found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the indent-item read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No indent item with the given id")
    })
    public ResponseEntity<IndentItemDto> getIndentItemById(@PathVariable Long id) {
        IndentItemDto indentItem = indentItemService.getIndentItemById(id);
        return ResponseEntity.ok(indentItem);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('indent-item:read') or hasAuthority('indent-item:admin')")
    @Operation(
            summary = "List all indent items",
            description = "Returns at most 500 rows. X-Total-Count carries the true total and X-Result-Capped is set when rows were left out; use the paginated variant for a complete result."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Indent items returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the indent-item read or admin authority")
    })
    public ResponseEntity<List<IndentItemDto>> getAllIndentItems() {
        return UnpagedResultCap.respond(
                indentItemService.getAllIndentItems(UnpagedResultCap.firstPage()));
    }

    @GetMapping("/indent/{indentId}")
    @PreAuthorize("hasAuthority('indent-item:read') or hasAuthority('indent-item:admin')")
    @Operation(
            summary = "List items for an indent",
            description = "Returns every item belonging to the given indent."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Indent items returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the indent-item read or admin authority")
    })
    public ResponseEntity<List<IndentItemDto>> getIndentItemsByIndentId(@PathVariable Long indentId) {
        List<IndentItemDto> indentItems = indentItemService.getIndentItemsByIndentId(indentId);
        return ResponseEntity.ok(indentItems);
    }

    @GetMapping("/material/{materialId}")
    @PreAuthorize("hasAuthority('indent-item:read') or hasAuthority('indent-item:admin')")
    @Operation(
            summary = "List items for a material",
            description = "Returns every indent item that requests the given material, across all indents."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Indent items returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the indent-item read or admin authority")
    })
    public ResponseEntity<List<IndentItemDto>> getIndentItemsByMaterialId(@PathVariable Long materialId) {
        List<IndentItemDto> indentItems = indentItemService.getIndentItemsByMaterialId(materialId);
        return ResponseEntity.ok(indentItems);
    }

    @GetMapping("/conversion-status")
    @PreAuthorize("hasAuthority('indent-item:read') or hasAuthority('indent-item:admin')")
    @Operation(
            summary = "List items by conversion status",
            description = "Returns every indent item whose converted-to-purchase-order flag matches the "
                    + "given value."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Indent items returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the indent-item read or admin authority")
    })
    public ResponseEntity<List<IndentItemDto>> getIndentItemsByConversionStatus(
            @RequestParam Boolean converted
    ) {
        List<IndentItemDto> indentItems = indentItemService.getIndentItemsByConversionStatus(converted);
        return ResponseEntity.ok(indentItems);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('indent-item:update') or hasAuthority('indent-item:admin')")
    @Operation(
            summary = "Update an indent item",
            description = "Replaces an indent item's material, quantities and remarks."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Indent item updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the indent-item update or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No indent item with the given id")
    })
    public ResponseEntity<IndentItemDto> updateIndentItem(
            @PathVariable Long id,
            @Valid @RequestBody IndentItemCreationDto updateDto
    ) {
        IndentItemDto updated = indentItemService.updateIndentItem(id, updateDto);
        return ResponseEntity.ok(updated);
    }

    @PutMapping("/{id}/mark-converted")
    @PreAuthorize("hasAuthority('indent-item:update') or hasAuthority('indent-item:admin')")
    @Operation(
            summary = "Mark an indent item as converted",
            description = "Marks an indent item as converted to a purchase order and records the linked "
                    + "purchase order number."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Indent item marked converted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the indent-item update or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No indent item with the given id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "The indent item is already marked as converted")
    })
    public ResponseEntity<IndentItemDto> markAsConverted(
            @PathVariable Long id,
            @RequestParam String purchaseOrderNumber
    ) {
        IndentItemDto updated = indentItemService.markAsConverted(id, purchaseOrderNumber);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('indent-item:delete') or hasAuthority('indent-item:admin')")
    @Operation(
            summary = "Delete an indent item",
            description = "Deletes the indent item with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Indent item deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the indent-item delete or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No indent item with the given id")
    })
    public ResponseEntity<ApiResponse> deleteIndentItem(@PathVariable Long id) {
        indentItemService.deleteIndentItem(id);
        return ResponseEntity.ok(new ApiResponse("IndentItem with id: " + id + " deleted successfully"));
    }
}
