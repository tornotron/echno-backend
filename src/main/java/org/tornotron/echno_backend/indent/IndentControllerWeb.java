package org.tornotron.echno_backend.indent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.indent.dto.IndentUpdateDto;
import org.tornotron.echno_backend.indentItem.dto.IndentItemCreationDto;
import org.tornotron.echno_backend.indentItem.dto.IndentItemDto;
import org.tornotron.echno_backend.indentItem.dto.IndentItemUpdateDto;
import org.tornotron.echno_backend.indent.dto.IndentCreationDto;
import org.tornotron.echno_backend.indent.dto.IndentDto;
import org.tornotron.echno_backend.indent.dto.IndentSummaryDto;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;

import java.util.List;

@RestController
@RequestMapping("/api/v1/indents/web")
@Tag(
        name = "Indents (Web)",
        description = "Web-console mirror of the indent endpoints, with an additional partial-update "
                + "operation not exposed on the mobile API. Access is gated by the web app's org-role "
                + "check instead of point authorities."
)
public class IndentControllerWeb {

    private final IndentService indentService;

    public IndentControllerWeb(IndentService indentService) {
        this.indentService = indentService;
    }

    // ==================== Indent Endpoints ====================

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Create an indent",
            description = "Creates a material indent for a project, along with its requested items."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Indent created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A required field is missing or failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<IndentDto> createIndent(@Valid @RequestBody IndentCreationDto indentCreationDto) {
        return new ResponseEntity<>(indentService.addIndent(indentCreationDto), HttpStatus.CREATED);
    }

    @GetMapping("/all")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List indents, paginated",
            description = "Returns a single page of indents. The pageNo and pageSize parameters control paging."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of indents returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<IndentDto>> getAllIndents(
            @Valid @ParameterObject PageQuery pageQuery
    ) {
        return new ResponseEntity<>(indentService.getAllIndents(pageQuery.getPageNo(), pageQuery.getPageSize()).getContent(), HttpStatus.OK);
    }

    /**
     * Retrieves a page of indents as summaries, for a list that does not read their lines.
     *
     * <p>The same indents, the same order and the same paging as {@link #getAllIndents(PageQuery)},
     * carrying the indent's own fields, the id and name of whoever raised it, and how many lines it
     * has. The lines themselves are on the detail view. That is the whole point: a line carries a
     * full material and a material carries stock figures read from a further aggregate, so a page
     * of indents was materialising a slice of the material catalogue to render a column of indent
     * numbers.
     *
     * <p>It is an addition, not a replacement. {@link #getAllIndents(PageQuery)} is unchanged.
     *
     * @param pageQuery Page index and page size, bounded by {@link PageQuery}.
     * @return A {@link ResponseEntity} containing the page of indent summaries.
     */
    @GetMapping("/summary")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List indents as summaries, paginated",
            description = "Returns a single page of indents without their item lines, each "
                    + "carrying how many lines it has and who raised it. Use this for lists; use "
                    + "the full listing or the detail endpoint when the lines are needed."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of indent summaries returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<Page<IndentSummaryDto>> getAllIndentSummaries(
            @Valid @ParameterObject PageQuery pageQuery
    ) {
        return new ResponseEntity<>(
                indentService.getAllIndentsSummary(pageQuery.getPageNo(), pageQuery.getPageSize()),
                HttpStatus.OK);
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List all indents",
            description = "Returns at most 500 rows. X-Total-Count carries the true total and X-Result-Capped is set when rows were left out; use the paginated variant for a complete result."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Indents returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<IndentDto>> getAllIndents() {
        return UnpagedResultCap.respond(indentService.getAllIndents(0, UnpagedResultCap.MAX_ROWS));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Get an indent by id",
            description = "Returns a single indent, including its creator, project and requested items."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Indent found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No indent with the given id")
    })
    public ResponseEntity<IndentDto> getAnIndent(@PathVariable Long id) {
        return new ResponseEntity<>(indentService.getAnIndent(id), HttpStatus.OK);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Update an indent",
            description = "Applies a partial update to an indent's number, project, status, expected date "
                    + "or remarks."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Indent updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No indent with the given id")
    })
    public ResponseEntity<IndentDto> UpdateAnIndent(
            @PathVariable Long id,
            @Valid @RequestBody IndentUpdateDto updateDto
            ) {
        IndentDto indentDto = indentService.updateIndent(id,updateDto);
        return ResponseEntity.ok(indentDto);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Delete an indent",
            description = "Deletes the indent with the given id, along with its items."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Indent deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No indent with the given id")
    })
    public ResponseEntity<ApiResponse> deleteIndent(@PathVariable Long id) {
        indentService.deleteIndent(id);
        return ResponseEntity.ok(new ApiResponse("Indent with id: " + id + " deleted successfully"));
    }

    // ==================== IndentItem Endpoints ====================

    @GetMapping("/{indentId}/items")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List items on an indent",
            description = "Returns every requested item on the given indent."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Indent items returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No indent with the given id")
    })
    public ResponseEntity<List<IndentItemDto>> getItems(@PathVariable Long indentId) {
        return ResponseEntity.ok(indentService.getItemsByIndentId(indentId));
    }

    @PostMapping("/{indentId}/items")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Add an item to an indent",
            description = "Adds a requested material item to an existing indent."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Indent item added"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A required field is missing or failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No indent with the given id")
    })
    public ResponseEntity<IndentItemDto> addItem(
            @PathVariable Long indentId,
            @Valid @RequestBody IndentItemCreationDto dto
    ) {
        return new ResponseEntity<>(indentService.addItem(indentId, dto), HttpStatus.CREATED);
    }

    @PatchMapping("/{indentId}/items/{itemId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Update an item on an indent",
            description = "Applies a partial update to a requested item's material, quantity or remarks."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Indent item updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No indent or indent item with the given id")
    })
    public ResponseEntity<IndentItemDto> updateItem(
            @PathVariable Long indentId,
            @PathVariable Long itemId,
            @Valid @RequestBody IndentItemUpdateDto dto
    ) {
        return ResponseEntity.ok(indentService.updateItem(indentId, itemId, dto));
    }

    @DeleteMapping("/{indentId}/items/{itemId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Delete an item from an indent",
            description = "Deletes the given item from the indent."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Indent item deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No indent or indent item with the given id")
    })
    public ResponseEntity<ApiResponse> deleteItem(
            @PathVariable Long indentId,
            @PathVariable Long itemId
    ) {
        indentService.deleteItem(indentId, itemId);
        return ResponseEntity.ok(new ApiResponse("IndentItem deleted successfully"));
    }
}
