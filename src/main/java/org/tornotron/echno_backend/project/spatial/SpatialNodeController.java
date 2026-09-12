package org.tornotron.echno_backend.project.spatial;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.project.spatial.dto.CreateSpatialNodeRequest;
import org.tornotron.echno_backend.project.spatial.dto.MoveSpatialNodeRequest;
import org.tornotron.echno_backend.project.spatial.dto.SpatialImportRequest;
import org.tornotron.echno_backend.project.spatial.dto.SpatialImportResult;
import org.tornotron.echno_backend.project.spatial.dto.SpatialNodeDto;
import org.tornotron.echno_backend.project.spatial.dto.SpatialTreeNodeDto;
import org.tornotron.echno_backend.project.spatial.dto.UpdateSpatialNodeRequest;

import java.util.List;
import java.util.UUID;

/**
 * A project's site structure: Building > Floor > Zone > Element. Project-scoped under the
 * existing {@code /api/v1/project/{projectId}} family, with no {@code /web} twin. Reads are
 * open to every member of the tenant; writes carry the same guard as the project patch path.
 * The org filter and the project id together make a node of another tenant or project a 404.
 */
@RestController
@RequestMapping("/api/v1/project/{projectId}/spatial")
@RequiredArgsConstructor
@Tag(name = "Project site structure", description = "Building, floor, zone and element tree of a project")
public class SpatialNodeController {

    private final SpatialNodeService service;

    @GetMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(summary = "Get the site structure tree",
            description = "The whole tree nested from buildings down. Archived nodes are left out "
                    + "unless includeArchived is set.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The tree"),
            @ApiResponse(responseCode = "404", description = "Project not found in the current tenant")
    })
    public ResponseEntity<List<SpatialTreeNodeDto>> getTree(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return ResponseEntity.ok(service.getTree(projectId, includeArchived));
    }

    @GetMapping("/nodes/{nodeId}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(summary = "Get one node with its path",
            description = "The node and the ordered ancestors from its building down to it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The node"),
            @ApiResponse(responseCode = "404", description = "Node not in this project, or project not in the tenant")
    })
    public ResponseEntity<SpatialNodeDto> getNode(@PathVariable Long projectId, @PathVariable UUID nodeId) {
        return ResponseEntity.ok(service.getNode(projectId, nodeId));
    }

    @PostMapping("/nodes")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(summary = "Add a node",
            description = "A building takes no parent; a floor, zone or element takes a parent of "
                    + "the level above. The code must be unique among siblings.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Node created"),
            @ApiResponse(responseCode = "404", description = "Parent not in this project"),
            @ApiResponse(responseCode = "409", description = "Sibling code clash or parent of the wrong level"),
            @ApiResponse(responseCode = "422", description = "Parent is archived")
    })
    public ResponseEntity<SpatialNodeDto> create(@PathVariable Long projectId,
                                                 @Valid @RequestBody CreateSpatialNodeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(projectId, request));
    }

    @PatchMapping("/nodes/{nodeId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(summary = "Update a node's fields",
            description = "Omitted fields are left as they are. Level and parent do not change here; use move.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Node updated"),
            @ApiResponse(responseCode = "404", description = "Node not in this project"),
            @ApiResponse(responseCode = "409", description = "Sibling code clash or BIM guid already used"),
            @ApiResponse(responseCode = "422", description = "Node is archived")
    })
    public ResponseEntity<SpatialNodeDto> update(@PathVariable Long projectId, @PathVariable UUID nodeId,
                                                 @Valid @RequestBody UpdateSpatialNodeRequest request) {
        return ResponseEntity.ok(service.update(projectId, nodeId, request));
    }

    @PostMapping("/nodes/{nodeId}/move")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(summary = "Move a node under another parent",
            description = "Same project, parent of the level above, not into the node's own subtree. "
                    + "Descendant paths are rewritten.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Node moved"),
            @ApiResponse(responseCode = "404", description = "Node or target not in this project"),
            @ApiResponse(responseCode = "409", description = "Wrong target level, own subtree, sibling code clash, or a building"),
            @ApiResponse(responseCode = "422", description = "Node or target is archived")
    })
    public ResponseEntity<SpatialNodeDto> move(@PathVariable Long projectId, @PathVariable UUID nodeId,
                                               @Valid @RequestBody MoveSpatialNodeRequest request) {
        return ResponseEntity.ok(service.move(projectId, nodeId, request.parentId()));
    }

    @PostMapping("/nodes/{nodeId}/archive")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(summary = "Archive a node and its subtree",
            description = "The node leaves pickers and refuses new references; existing references stay valid.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Node archived"),
            @ApiResponse(responseCode = "404", description = "Node not in this project")
    })
    public ResponseEntity<SpatialNodeDto> archive(@PathVariable Long projectId, @PathVariable UUID nodeId) {
        return ResponseEntity.ok(service.archive(projectId, nodeId));
    }

    @PostMapping("/nodes/{nodeId}/restore")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(summary = "Restore an archived node",
            description = "Brings back the node and the descendants archived with it. The parent must be active.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Node restored"),
            @ApiResponse(responseCode = "404", description = "Node not in this project"),
            @ApiResponse(responseCode = "422", description = "Parent is archived")
    })
    public ResponseEntity<SpatialNodeDto> restore(@PathVariable Long projectId, @PathVariable UUID nodeId) {
        return ResponseEntity.ok(service.restore(projectId, nodeId));
    }

    @PostMapping("/import")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(summary = "Import a site structure from rows",
            description = "One row per leaf, codes from the building down. Idempotent on the code path: "
                    + "nodes that already exist are skipped and counted.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Counts of created and skipped nodes"),
            @ApiResponse(responseCode = "400", description = "A row names a zone or element without the levels above it"),
            @ApiResponse(responseCode = "404", description = "Project not found in the current tenant"),
            @ApiResponse(responseCode = "422", description = "A node on the path is archived")
    })
    public ResponseEntity<SpatialImportResult> importRows(@PathVariable Long projectId,
                                                          @Valid @RequestBody SpatialImportRequest request) {
        return ResponseEntity.ok(service.importRows(projectId, request.rows()));
    }
}
