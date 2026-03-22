package org.tornotron.echno_backend.indent;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.indentItem.dto.IndentItemCreationDto;
import org.tornotron.echno_backend.indentItem.dto.IndentItemDto;
import org.tornotron.echno_backend.indent.dto.IndentCreationDto;
import org.tornotron.echno_backend.indent.dto.IndentDto;

import java.util.List;

@RestController
@RequestMapping("/api/v1/indents/web")
public class IndentControllerWeb {

    private final IndentService indentService;

    public IndentControllerWeb(IndentService indentService) {
        this.indentService = indentService;
    }

    // ==================== Indent Endpoints ====================

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<IndentDto> createIndent(@Valid @RequestBody IndentCreationDto indentCreationDto) {
        return new ResponseEntity<>(indentService.addIndent(indentCreationDto), HttpStatus.CREATED);
    }

    @GetMapping("/all")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<List<IndentDto>> getAllIndents(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        return new ResponseEntity<>(indentService.getAllIndents(pageNo, pageSize).getContent(), HttpStatus.OK);
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<List<IndentDto>> getAllIndents() {
        return new ResponseEntity<>(indentService.getAllIndents(), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<IndentDto> getAnIndent(@PathVariable Long id) {
        return new ResponseEntity<>(indentService.getAnIndent(id), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<ApiResponse> deleteIndent(@PathVariable Long id) {
        indentService.deleteIndent(id);
        return ResponseEntity.ok(new ApiResponse("Indent with id: " + id + " deleted successfully"));
    }

    // ==================== IndentItem Endpoints ====================

    @GetMapping("/{indentId}/items")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<List<IndentItemDto>> getItems(@PathVariable Long indentId) {
        return ResponseEntity.ok(indentService.getItemsByIndentId(indentId));
    }

    @PostMapping("/{indentId}/items")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<IndentItemDto> addItem(
            @PathVariable Long indentId,
            @Valid @RequestBody IndentItemCreationDto dto
    ) {
        return new ResponseEntity<>(indentService.addItem(indentId, dto), HttpStatus.CREATED);
    }

    @PutMapping("/{indentId}/items/{itemId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<IndentItemDto> updateItem(
            @PathVariable Long indentId,
            @PathVariable Long itemId,
            @Valid @RequestBody IndentItemCreationDto dto
    ) {
        return ResponseEntity.ok(indentService.updateItem(indentId, itemId, dto));
    }

    @DeleteMapping("/{indentId}/items/{itemId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<ApiResponse> deleteItem(
            @PathVariable Long indentId,
            @PathVariable Long itemId
    ) {
        indentService.deleteItem(indentId, itemId);
        return ResponseEntity.ok(new ApiResponse("IndentItem deleted successfully"));
    }
}
