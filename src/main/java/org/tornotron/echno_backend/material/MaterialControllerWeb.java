package org.tornotron.echno_backend.material;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.material.dto.MaterialCreationDto;
import org.tornotron.echno_backend.material.dto.MaterialDto;
import org.tornotron.echno_backend.material.dto.MaterialWithStockDto;

import java.util.List;

@RestController
@RequestMapping("/api/v1/materials/web")
@Validated
public class MaterialControllerWeb {

    private final MaterialService materialService;

    public MaterialControllerWeb(MaterialService materialService) {
        this.materialService = materialService;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<MaterialDto> createMaterial(@Valid @RequestBody MaterialCreationDto creationDto) {
        MaterialDto created = materialService.createMaterial(creationDto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<MaterialDto> getMaterialById(@PathVariable Long id) {
        MaterialDto material = materialService.getMaterialById(id);
        return ResponseEntity.ok(material);
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<List<MaterialDto>> getAllMaterials() {
        List<MaterialDto> materials = materialService.getAllMaterials();
        return ResponseEntity.ok(materials);
    }

    @GetMapping("/all")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<Page<MaterialDto>> getAllMaterialsPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        Page<MaterialDto> materials = materialService.getAllMaterials(pageNo, pageSize);
        return ResponseEntity.ok(materials);
    }

    @GetMapping("/search")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<List<MaterialDto>> searchMaterials(@RequestParam String name) {
        List<MaterialDto> materials = materialService.searchMaterialsByName(name);
        return ResponseEntity.ok(materials);
    }

    @GetMapping("/{id}/stock")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<MaterialWithStockDto> getMaterialWithStock(
            @PathVariable Long id,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long storageLocationId) {
        MaterialWithStockDto material;
        if (projectId != null && storageLocationId != null) {
            material = materialService.getMaterialStockAtLocation(id, projectId, storageLocationId);
        } else if (projectId != null) {
            material = materialService.getMaterialWithCurrentStock(id, projectId);
        } else {
            material = materialService.getMaterialWithAggregateStock(id);
        }
        return ResponseEntity.ok(material);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<MaterialDto> updateMaterial(
            @PathVariable Long id,
            @Valid @RequestBody org.tornotron.echno_backend.material.dto.MaterialUpdateDto updateDto
    ) {
        MaterialDto updated = materialService.updateMaterial(id, updateDto);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<ApiResponse> deleteMaterial(@PathVariable Long id) {
        materialService.deleteMaterial(id);
        return ResponseEntity.ok(new ApiResponse("Material with id: " + id + " deleted successfully"));
    }
}
