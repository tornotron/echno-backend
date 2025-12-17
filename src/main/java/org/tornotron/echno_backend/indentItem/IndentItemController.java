package org.tornotron.echno_backend.indentItem;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.indentItem.dto.IndentItemCreationDto;
import org.tornotron.echno_backend.indentItem.dto.IndentItemDto;

import java.util.List;

@RestController
@RequestMapping("/api/v1/indent-items")
@Validated
public class IndentItemController {

    private final IndentItemService indentItemService;

    public IndentItemController(IndentItemService indentItemService) {
        this.indentItemService = indentItemService;
    }

    @PostMapping
    public ResponseEntity<IndentItemDto> createIndentItem(@Valid @RequestBody IndentItemCreationDto creationDto) {
        IndentItemDto created = indentItemService.createIndentItem(creationDto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    public ResponseEntity<IndentItemDto> getIndentItemById(@PathVariable Long id) {
        IndentItemDto indentItem = indentItemService.getIndentItemById(id);
        return ResponseEntity.ok(indentItem);
    }

    @GetMapping
    public ResponseEntity<List<IndentItemDto>> getAllIndentItems() {
        List<IndentItemDto> indentItems = indentItemService.getAllIndentItems();
        return ResponseEntity.ok(indentItems);
    }

    @GetMapping("/intend/{intendId}")
    public ResponseEntity<List<IndentItemDto>> getIndentItemsByIntendId(@PathVariable Long intendId) {
        List<IndentItemDto> indentItems = indentItemService.getIndentItemsByIntendId(intendId);
        return ResponseEntity.ok(indentItems);
    }

    @GetMapping("/material/{materialId}")
    public ResponseEntity<List<IndentItemDto>> getIndentItemsByMaterialId(@PathVariable Long materialId) {
        List<IndentItemDto> indentItems = indentItemService.getIndentItemsByMaterialId(materialId);
        return ResponseEntity.ok(indentItems);
    }

    @GetMapping("/conversion-status")
    public ResponseEntity<List<IndentItemDto>> getIndentItemsByConversionStatus(
            @RequestParam Boolean converted
    ) {
        List<IndentItemDto> indentItems = indentItemService.getIndentItemsByConversionStatus(converted);
        return ResponseEntity.ok(indentItems);
    }

    @PutMapping("/{id}")
    public ResponseEntity<IndentItemDto> updateIndentItem(
            @PathVariable Long id,
            @Valid @RequestBody IndentItemCreationDto updateDto
    ) {
        IndentItemDto updated = indentItemService.updateIndentItem(id, updateDto);
        return ResponseEntity.ok(updated);
    }

    @PutMapping("/{id}/mark-converted")
    public ResponseEntity<IndentItemDto> markAsConverted(
            @PathVariable Long id,
            @RequestParam String purchaseOrderNumber
    ) {
        IndentItemDto updated = indentItemService.markAsConverted(id, purchaseOrderNumber);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse> deleteIndentItem(@PathVariable Long id) {
        indentItemService.deleteIndentItem(id);
        return ResponseEntity.ok(new ApiResponse("IndentItem with id: " + id + " deleted successfully"));
    }
}
