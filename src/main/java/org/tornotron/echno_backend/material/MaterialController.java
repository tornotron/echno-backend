package org.tornotron.echno_backend.material;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.material.dto.MaterialCreationDto;
import org.tornotron.echno_backend.material.dto.MaterialDto;
import org.tornotron.echno_backend.material.dto.MaterialWithStockDto;
import org.tornotron.echno_backend.material.threshold.MaterialLocationThresholdService;
import org.tornotron.echno_backend.material.threshold.dto.MaterialLocationThresholdDto;
import org.tornotron.echno_backend.material.threshold.dto.MaterialLocationThresholdUpsertDto;

import java.util.List;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;

@RestController
@RequestMapping("/api/v1/materials")
@Validated
@Tag(
        name = "Materials",
        description = "Materials tracked in the inventory catalogue, such as cement, TMT bars and sand. "
                + "A material carries an SKU, unit of measure, reorder thresholds and optional opening "
                + "stock at a project and storage location. Endpoints cover creating, browsing, searching "
                + "and updating materials, and reading their current stock at organization, project or "
                + "storage-location level."
)
public class MaterialController {

    private final MaterialService materialService;
    private final MaterialLocationThresholdService thresholdService;

    public MaterialController(MaterialService materialService,
                             MaterialLocationThresholdService thresholdService) {
        this.materialService = materialService;
        this.thresholdService = thresholdService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('material:create') or hasAuthority('material:admin')")
    @Operation(
            summary = "Create a material",
            description = "Creates a material with its SKU, unit and reorder thresholds. If an opening "
                    + "stock, project and storage location are supplied, an opening stock entry is "
                    + "recorded for that location as well."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Material created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation, such as a missing material name or unit"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the material create or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "A material with the given SKU already exists in this organization")
    })
    public ResponseEntity<MaterialDto> createMaterial(@Valid @RequestBody MaterialCreationDto creationDto) {
        MaterialDto created = materialService.createMaterial(creationDto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('material:read') or hasAuthority('material:admin')")
    @Operation(
            summary = "Get a material by id",
            description = "Returns a single material with its creator and current aggregate stock value."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Material found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the material read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No material with the given id")
    })
    public ResponseEntity<MaterialDto> getMaterialById(@PathVariable Long id) {
        MaterialDto material = materialService.getMaterialById(id);
        return ResponseEntity.ok(material);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('material:read') or hasAuthority('material:admin')")
    @Operation(
            summary = "List all materials",
            description = "Returns at most 500 rows. X-Total-Count carries the true total and X-Result-Capped is set when rows were left out; use the paginated variant for a complete result."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Materials returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the material read or admin authority")
    })
    public ResponseEntity<List<MaterialDto>> getAllMaterials() {
        return UnpagedResultCap.respond(materialService.getAllMaterials(0, UnpagedResultCap.MAX_ROWS));
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('material:read') or hasAuthority('material:admin')")
    @Operation(
            summary = "List materials, paginated",
            description = "Returns a single page of materials. The pageNo and pageSize parameters "
                    + "control paging."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of materials returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the material read or admin authority")
    })
    public ResponseEntity<Page<MaterialDto>> getAllMaterialsPaginated(
            @Valid @ParameterObject PageQuery pageQuery
    ) {
        Page<MaterialDto> materials = materialService.getAllMaterials(pageQuery.getPageNo(), pageQuery.getPageSize());
        return ResponseEntity.ok(materials);
    }

    @GetMapping("/search")
    @PreAuthorize("hasAuthority('material:read') or hasAuthority('material:admin')")
    @Operation(
            summary = "Search materials by name",
            description = "Returns materials whose name matches the given search term, such as \"Cement\" "
                    + "or \"TMT\"."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Matching materials returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the material read or admin authority")
    })
    public ResponseEntity<List<MaterialDto>> searchMaterials(@RequestParam String name) {
        List<MaterialDto> materials = materialService.searchMaterialsByName(name);
        return ResponseEntity.ok(materials);
    }

    @GetMapping("/{id}/stock")
    @PreAuthorize("hasAuthority('material:read') or hasAuthority('material:admin')")
    @Operation(
            summary = "Get a material with its current stock",
            description = "Returns a material along with its current stock. When projectId and "
                    + "storageLocationId are both given, stock is scoped to that storage location; when "
                    + "only projectId is given, stock is scoped to that project; when neither is given, "
                    + "the aggregate stock across the organization is returned."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Material with stock returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the material read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No material, project or storage location with the given id")
    })
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
    @PreAuthorize("hasAuthority('material:update') or hasAuthority('material:admin')")
    @Operation(
            summary = "Update a material",
            description = "Applies a partial update to a material's SKU, name, unit or reorder thresholds. "
                    + "Fields left null in the request are left unchanged."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Material updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the material update or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No material with the given id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Another material already has the given SKU")
    })
    public ResponseEntity<MaterialDto> updateMaterial(
            @PathVariable Long id,
            @Valid @RequestBody org.tornotron.echno_backend.material.dto.MaterialUpdateDto updateDto
    ) {
        MaterialDto updated = materialService.updateMaterial(id, updateDto);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('material:delete') or hasAuthority('material:admin')")
    @Operation(
            summary = "Delete a material",
            description = "Deletes the material with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Material deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the material delete or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No material with the given id")
    })
    public ResponseEntity<ApiResponse> deleteMaterial(@PathVariable Long id) {
        materialService.deleteMaterial(id);
        return ResponseEntity.ok(new ApiResponse("Material with id: " + id + " deleted successfully"));
    }

    @GetMapping("/{materialId}/location-thresholds")
    @PreAuthorize("hasAuthority('material:read') or hasAuthority('material:admin')")
    @Operation(
            summary = "List a material's per-location threshold overrides",
            description = "Returns every storage-location override of the material's planning thresholds. "
                    + "A location not listed here uses the material's global thresholds."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Overrides returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the material read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No material with the given id")
    })
    public ResponseEntity<List<MaterialLocationThresholdDto>> getLocationThresholds(@PathVariable Long materialId) {
        return ResponseEntity.ok(thresholdService.listForMaterial(materialId));
    }

    @PutMapping("/{materialId}/location-thresholds/{storageLocationId}")
    @PreAuthorize("hasAuthority('material:update') or hasAuthority('material:admin')")
    @Operation(
            summary = "Set a material's thresholds at a storage location",
            description = "Creates or replaces the material's threshold override at the given storage location. "
                    + "Any field left null clears that level so the material's global threshold applies."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Override saved"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the material update or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No material or storage location with the given id")
    })
    public ResponseEntity<MaterialLocationThresholdDto> upsertLocationThreshold(
            @PathVariable Long materialId,
            @PathVariable Long storageLocationId,
            @Valid @RequestBody MaterialLocationThresholdUpsertDto upsertDto) {
        return ResponseEntity.ok(thresholdService.upsert(materialId, storageLocationId, upsertDto));
    }

    @DeleteMapping("/{materialId}/location-thresholds/{storageLocationId}")
    @PreAuthorize("hasAuthority('material:update') or hasAuthority('material:admin')")
    @Operation(
            summary = "Remove a material's thresholds at a storage location",
            description = "Deletes the material's threshold override at the given storage location, so the "
                    + "material's global thresholds apply there again."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Override removed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the material update or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No override for the given material and storage location")
    })
    public ResponseEntity<Void> deleteLocationThreshold(
            @PathVariable Long materialId,
            @PathVariable Long storageLocationId) {
        thresholdService.delete(materialId, storageLocationId);
        return ResponseEntity.noContent().build();
    }
}
