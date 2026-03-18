package org.tornotron.echno_backend.wbs;

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
public class WbsElementControllerWeb {

    private final WbsElementService service;
    private static final Logger logger = LoggerFactory.getLogger(WbsElementControllerWeb.class);

    public WbsElementControllerWeb(WbsElementService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<WbsElementDto> createWbsElement(
            @PathVariable Long projectId,
            @Valid @RequestBody WbsElementCreationDto dto) {
        WbsElementDto created = service.createWbsElement(projectId, dto);
        logger.info("WBS element created with id: {}", created.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/bulk")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<List<WbsElementDto>> bulkCreateWbsElements(
            @PathVariable Long projectId,
            @Valid @RequestBody WbsBulkCreateDto dto) {
        List<WbsElementDto> created = service.bulkCreateWbsElements(projectId, dto);
        logger.info("Bulk created {} WBS elements for project {}", created.size(), projectId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/tree")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<List<WbsElementDto>> getWbsTree(@PathVariable Long projectId) {
        List<WbsElementDto> tree = service.getWbsTree(projectId);
        return ResponseEntity.ok(tree);
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<List<WbsElementFlatDto>> getWbsFlatList(@PathVariable Long projectId) {
        List<WbsElementFlatDto> elements = service.getWbsFlatList(projectId);
        return ResponseEntity.ok(elements);
    }

    @GetMapping("/{elementId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<WbsElementDto> getWbsElementById(@PathVariable Long projectId,
                                                           @PathVariable Long elementId) {
        WbsElementDto element = service.getWbsElementById(elementId);
        return ResponseEntity.ok(element);
    }

    @PutMapping("/{elementId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<WbsElementDto> updateWbsElement(@PathVariable Long projectId,
                                                          @PathVariable Long elementId,
                                                          @Valid @RequestBody WbsElementUpdateDto dto) {
        WbsElementDto updated = service.updateWbsElement(elementId, dto);
        logger.info("WBS element updated with id: {}", elementId);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{elementId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<ApiResponse> deleteWbsElement(@PathVariable Long projectId,
                                                        @PathVariable Long elementId) {
        service.deleteWbsElement(elementId);
        logger.info("WBS element deleted with id: {}", elementId);
        return ResponseEntity.ok(new ApiResponse("WBS element with id: " + elementId + " deleted"));
    }

    @PostMapping("/{elementId}/move")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<WbsElementDto> moveWbsElement(@PathVariable Long projectId,
                                                        @PathVariable Long elementId,
                                                        @Valid @RequestBody WbsMoveDto dto) {
        WbsElementDto moved = service.moveWbsElement(elementId, dto);
        logger.info("WBS element moved with id: {}", elementId);
        return ResponseEntity.ok(moved);
    }

    @GetMapping("/leaves")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<List<WbsElementFlatDto>> getLeafElements(@PathVariable Long projectId) {
        List<WbsElementFlatDto> leaves = service.getLeafElements(projectId);
        return ResponseEntity.ok(leaves);
    }

    @PostMapping("/{elementId}/recalculate")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<WbsElementDto> recalculateElement(@PathVariable Long projectId,
                                                            @PathVariable Long elementId) {
        WbsElementDto recalculated = service.recalculateElement(elementId);
        logger.info("WBS element recalculated with id: {}", elementId);
        return ResponseEntity.ok(recalculated);
    }
}
