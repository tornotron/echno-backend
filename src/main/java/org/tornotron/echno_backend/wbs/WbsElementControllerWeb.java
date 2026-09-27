package org.tornotron.echno_backend.wbs;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.wbs.dto.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/project/{projectId}/wbs/web")
@Validated
@Tag(
        name = "WBS Elements (Web)",
        description = "Web-client counterpart of the WBS Elements endpoints, serving the same work "
                + "breakdown structure operations under the /web path segment: create single or batch "
                + "elements, read the tree or a flat list, update, delete, move an element under a "
                + "different parent, and recalculate rolled-up cost and progress. Any member of the tenant "
                + "reads; the system-admin and project-manager roles change the schedule."
)
public class WbsElementControllerWeb {

    private final WbsElementService service;
    private static final Logger logger = LoggerFactory.getLogger(WbsElementControllerWeb.class);

    public WbsElementControllerWeb(WbsElementService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize(WbsAccess.MANAGE_GUARD)
    @Operation(
            summary = "Create a WBS element",
            description = "Creates a single work breakdown structure element under the given project, "
                    + "for example \"RCC Column Casting - Block A\" with wbsCode \"1.2.3\". Pass parentId "
                    + "to nest the element under an existing one; the wbsCode must be unique within the "
                    + "project."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "WBS element created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The request body failed validation, or the parent element belongs to a different project"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No project, or no parent element, with the given id in this organization"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "wbsCode already exists in this project")
    })
    public ResponseEntity<WbsElementDto> createWbsElement(
            @PathVariable Long projectId,
            @Valid @RequestBody WbsElementCreationDto dto) {
        WbsElementDto created = service.createWbsElement(projectId, dto);
        logger.info("WBS element created with id: {}", created.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/bulk")
    @PreAuthorize(WbsAccess.MANAGE_GUARD)
    @Operation(
            summary = "Bulk create WBS elements",
            description = "Creates a batch of work breakdown structure elements under the given project "
                    + "in one call, for example seeding \"Foundation Works\", \"Electrical First Fix\" and "
                    + "their sub-activities together. Each entry is created the same way as the single "
                    + "create endpoint and is validated the same way."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "WBS elements created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The elements list is missing or one entry failed validation, or a parent element belongs to a different project"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No project, or no parent element, with the given id in this organization"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "wbsCode already exists in this project for one of the entries")
    })
    public ResponseEntity<List<WbsElementDto>> bulkCreateWbsElements(
            @PathVariable Long projectId,
            @Valid @RequestBody WbsBulkCreateDto dto) {
        List<WbsElementDto> created = service.bulkCreateWbsElements(projectId, dto);
        logger.info("Bulk created {} WBS elements for project {}", created.size(), projectId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/tree")
    @PreAuthorize(WbsAccess.READ_GUARD)
    @Operation(
            summary = "Get the WBS tree",
            description = "Returns the project's work breakdown structure as a tree, starting from the "
                    + "root elements with their children nested underneath in sort order."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "WBS tree returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No project with the given id in this organization")
    })
    public ResponseEntity<List<WbsElementDto>> getWbsTree(@PathVariable Long projectId) {
        List<WbsElementDto> tree = service.getWbsTree(projectId);
        return ResponseEntity.ok(tree);
    }

    @GetMapping
    @PreAuthorize(WbsAccess.READ_GUARD)
    @Operation(
            summary = "List WBS elements",
            description = "Returns every WBS element in the project as a flat list ordered by wbsCode, "
                    + "without the parent/child nesting used by the tree endpoint."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "WBS elements returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No project with the given id in this organization")
    })
    public ResponseEntity<List<WbsElementFlatDto>> getWbsFlatList(@PathVariable Long projectId) {
        List<WbsElementFlatDto> elements = service.getWbsFlatList(projectId);
        return ResponseEntity.ok(elements);
    }

    @GetMapping("/{elementId}")
    @PreAuthorize(WbsAccess.READ_GUARD)
    @Operation(
            summary = "Get a WBS element by id",
            description = "Returns a single WBS element and its children, for example the \"Foundation "
                    + "Works\" node together with its \"Excavation\" and \"PCC\" sub-elements."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "WBS element found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No WBS element with the given id in this organization")
    })
    public ResponseEntity<WbsElementDto> getWbsElementById(@PathVariable Long projectId,
                                                           @PathVariable Long elementId) {
        WbsElementDto element = service.getWbsElementById(elementId);
        return ResponseEntity.ok(element);
    }

    @PutMapping("/{elementId}")
    @PreAuthorize(WbsAccess.MANAGE_GUARD)
    @Operation(
            summary = "Update a WBS element",
            description = "Applies a partial update to a WBS element: only the fields present in the "
                    + "request body are changed. Setting progress is only allowed on a leaf element; "
                    + "setting it on a leaf recalculates the progress rolled up to its ancestors."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "WBS element updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The request body failed validation, or progress was set on an element that is not a leaf"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No WBS element with the given id in this organization")
    })
    public ResponseEntity<WbsElementDto> updateWbsElement(@PathVariable Long projectId,
                                                          @PathVariable Long elementId,
                                                          @Valid @RequestBody WbsElementUpdateDto dto) {
        WbsElementDto updated = service.updateWbsElement(elementId, dto);
        logger.info("WBS element updated with id: {}", elementId);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{elementId}")
    @PreAuthorize(WbsAccess.MANAGE_GUARD)
    @Operation(
            summary = "Delete a WBS element",
            description = "Deletes the WBS element with the given id. If it was the last remaining child "
                    + "of its parent, the parent is marked as a leaf again and its progress is "
                    + "recalculated."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "WBS element deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No WBS element with the given id in this organization")
    })
    public ResponseEntity<ApiResponse> deleteWbsElement(@PathVariable Long projectId,
                                                        @PathVariable Long elementId) {
        service.deleteWbsElement(elementId);
        logger.info("WBS element deleted with id: {}", elementId);
        return ResponseEntity.ok(new ApiResponse("WBS element with id: " + elementId + " deleted"));
    }

    @PostMapping("/{elementId}/move")
    @PreAuthorize(WbsAccess.MANAGE_GUARD)
    @Operation(
            summary = "Move a WBS element",
            description = "Reparents a WBS element under a different parent (or to the project root when "
                    + "newParentId is omitted), and optionally reassigns its wbsCode and sortOrder in the "
                    + "same call. Levels are recalculated down through the element's own descendants."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "WBS element moved"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The new parent belongs to a different project, or the move would place the element under its own descendant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No WBS element, or no new parent element, with the given id in this organization"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "newWbsCode already exists in this project on a different element")
    })
    public ResponseEntity<WbsElementDto> moveWbsElement(@PathVariable Long projectId,
                                                        @PathVariable Long elementId,
                                                        @Valid @RequestBody WbsMoveDto dto) {
        WbsElementDto moved = service.moveWbsElement(elementId, dto);
        logger.info("WBS element moved with id: {}", elementId);
        return ResponseEntity.ok(moved);
    }

    @GetMapping("/leaves")
    @PreAuthorize(WbsAccess.READ_GUARD)
    @Operation(
            summary = "List leaf WBS elements",
            description = "Returns the leaf elements of the project's WBS, the elements with no children, "
                    + "ordered by wbsCode. These are the elements progress can be set on directly."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Leaf WBS elements returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No project with the given id in this organization")
    })
    public ResponseEntity<List<WbsElementFlatDto>> getLeafElements(@PathVariable Long projectId) {
        List<WbsElementFlatDto> leaves = service.getLeafElements(projectId);
        return ResponseEntity.ok(leaves);
    }

    @PostMapping("/{elementId}/recalculate")
    @PreAuthorize(WbsAccess.MANAGE_GUARD)
    @Operation(
            summary = "Recalculate a WBS element",
            description = "Recomputes the actual cost and progress of a non-leaf WBS element from its "
                    + "descendants, cost by summing and progress by weighted average, and saves the "
                    + "result."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "WBS element recalculated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No WBS element with the given id in this organization")
    })
    public ResponseEntity<WbsElementDto> recalculateElement(@PathVariable Long projectId,
                                                            @PathVariable Long elementId) {
        WbsElementDto recalculated = service.recalculateElement(elementId);
        logger.info("WBS element recalculated with id: {}", elementId);
        return ResponseEntity.ok(recalculated);
    }

    @GetMapping("/schedule")
    @PreAuthorize(WbsAccess.READ_GUARD)
    @Operation(
            summary = "Get the project schedule",
            description = "Returns every WBS element of the project as a flat row ordered by wbsCode, with "
                    + "planned, actual and forecast dates, the milestone flag, the responsible employee or "
                    + "sub-contractor by name, and the delay in days, together with every dependency link. "
                    + "Nothing is rescheduled by this read; delay is worked out against the planned finish."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Schedule returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No project with the given id in this organization")
    })
    public ResponseEntity<WbsScheduleDto> getSchedule(@PathVariable Long projectId) {
        return ResponseEntity.ok(service.getSchedule(projectId));
    }

    @PostMapping("/dependencies")
    @PreAuthorize(WbsAccess.MANAGE_GUARD)
    @Operation(
            summary = "Link two activities",
            description = "Records that one activity depends on another in the same project, finish-to-start "
                    + "by default, with an optional lag in days. A link that would close a loop is refused. "
                    + "No date moves because of the link."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Link recorded"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The body failed validation, the two ids are the same activity, or the link would close a loop"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No project, or no such activity in this project"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "The two activities are already linked")
    })
    public ResponseEntity<WbsDependencyDto> addDependency(@PathVariable Long projectId,
                                                          @Valid @RequestBody WbsDependencyCreationDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addDependency(projectId, dto));
    }

    @DeleteMapping("/dependencies/{dependencyId}")
    @PreAuthorize(WbsAccess.MANAGE_GUARD)
    @Operation(summary = "Remove a link between two activities")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Link removed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No such link in this project")
    })
    public ResponseEntity<ApiResponse> removeDependency(@PathVariable Long projectId, @PathVariable Long dependencyId) {
        service.removeDependency(projectId, dependencyId);
        return ResponseEntity.ok(new ApiResponse("Dependency with id: " + dependencyId + " removed"));
    }
}
