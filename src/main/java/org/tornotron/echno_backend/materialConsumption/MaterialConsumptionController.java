package org.tornotron.echno_backend.materialConsumption;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.materialConsumption.dto.MaterialConsumptionCreationDto;
import org.tornotron.echno_backend.materialConsumption.dto.MaterialConsumptionDto;
import org.tornotron.echno_backend.materialConsumption.enums.MaterialConsumptionType;

import java.time.LocalDateTime;
import java.util.List;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;

@RestController
@RequestMapping("/api/v1/material-consumptions")
@Validated
@Tag(
        name = "Material Consumptions",
        description = "Records of material drawn out of stock against a project, optionally a storage "
                + "location and a task. Each consumption reduces the material's current stock, so "
                + "creating one checks that sufficient stock is available first. Endpoints cover "
                + "recording a consumption and reading consumptions by id, material, type, date range or task."
)
public class MaterialConsumptionController {

    private final MaterialConsumptionService materialConsumptionService;

    public MaterialConsumptionController(MaterialConsumptionService materialConsumptionService) {
        this.materialConsumptionService = materialConsumptionService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('material-consumption:create') or hasAuthority('material-consumption:admin')")
    @Operation(
            summary = "Record a material consumption",
            description = "Records material consumed against a project, and optionally a storage "
                    + "location and task, after checking that enough stock is available at the relevant "
                    + "level."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Consumption recorded"),
            @ApiResponse(responseCode = "400", description = "A field failed validation, such as a missing material id or a non-positive quantity"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the material-consumption create or admin authority"),
            @ApiResponse(responseCode = "404", description = "No material, employee, project, storage location or task with the given id"),
            @ApiResponse(responseCode = "409", description = "Insufficient stock at the relevant material, project or storage location for the requested quantity")
    })
    public ResponseEntity<MaterialConsumptionDto> createMaterialConsumption(
            @Valid @RequestBody MaterialConsumptionCreationDto creationDto) {
        MaterialConsumptionDto created = materialConsumptionService.createMaterialConsumption(creationDto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('material-consumption:read') or hasAuthority('material-consumption:admin')")
    @Operation(
            summary = "Get a material consumption by id",
            description = "Returns a single material consumption record with its material, project, "
                    + "storage location, task and creator resolved."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Consumption found"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the material-consumption read or admin authority"),
            @ApiResponse(responseCode = "404", description = "No material consumption with the given id")
    })
    public ResponseEntity<MaterialConsumptionDto> getMaterialConsumptionById(@PathVariable Long id) {
        MaterialConsumptionDto consumption = materialConsumptionService.getMaterialConsumptionById(id);
        return ResponseEntity.ok(consumption);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('material-consumption:read') or hasAuthority('material-consumption:admin')")
    @Operation(
            summary = "List all material consumptions",
            description = "Returns at most 500 rows. X-Total-Count carries the true total and X-Result-Capped is set when rows were left out; use the paginated variant for a complete result."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Consumptions returned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the material-consumption read or admin authority")
    })
    public ResponseEntity<List<MaterialConsumptionDto>> getAllMaterialConsumptions() {
        return UnpagedResultCap.respond(
                materialConsumptionService.getAllMaterialConsumptions(0, UnpagedResultCap.MAX_ROWS));
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('material-consumption:read') or hasAuthority('material-consumption:admin')")
    @Operation(
            summary = "List material consumptions, paginated",
            description = "Returns a single page of material consumption records. The pageNo and "
                    + "pageSize parameters control paging."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of consumptions returned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the material-consumption read or admin authority")
    })
    public ResponseEntity<Page<MaterialConsumptionDto>> getAllMaterialConsumptionsPaginated(
            @Valid @ParameterObject PageQuery pageQuery
    ) {
        Page<MaterialConsumptionDto> consumptions = materialConsumptionService.getAllMaterialConsumptions(pageQuery.getPageNo(), pageQuery.getPageSize());
        return ResponseEntity.ok(consumptions);
    }

    @GetMapping("/material/{materialId}")
    @PreAuthorize("hasAuthority('material-consumption:read') or hasAuthority('material-consumption:admin')")
    @Operation(
            summary = "List consumptions for a material",
            description = "Returns every consumption record for the given material, such as all draws "
                    + "of TMT Bar 12mm across every project."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Consumptions returned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the material-consumption read or admin authority"),
            @ApiResponse(responseCode = "404", description = "No material with the given id")
    })
    public ResponseEntity<List<MaterialConsumptionDto>> getConsumptionsByMaterial(@PathVariable Long materialId) {
        List<MaterialConsumptionDto> consumptions = materialConsumptionService.getConsumptionsByMaterial(materialId);
        return ResponseEntity.ok(consumptions);
    }

    @GetMapping("/type/{consumptionType}")
    @PreAuthorize("hasAuthority('material-consumption:read') or hasAuthority('material-consumption:admin')")
    @Operation(
            summary = "List consumptions by type",
            description = "Returns every consumption record of the given consumption type, such as "
                    + "wastage or normal usage."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Consumptions returned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the material-consumption read or admin authority")
    })
    public ResponseEntity<List<MaterialConsumptionDto>> getConsumptionsByType(@PathVariable MaterialConsumptionType consumptionType) {
        List<MaterialConsumptionDto> consumptions = materialConsumptionService.getConsumptionsByType(consumptionType);
        return ResponseEntity.ok(consumptions);
    }

    @GetMapping("/date-range")
    @PreAuthorize("hasAuthority('material-consumption:read') or hasAuthority('material-consumption:admin')")
    @Operation(
            summary = "List consumptions in a date range",
            description = "Returns every consumption record whose consumption date falls between "
                    + "startDate and endDate, inclusive."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Consumptions returned"),
            @ApiResponse(responseCode = "400", description = "startDate or endDate is missing or not a valid ISO date-time"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the material-consumption read or admin authority")
    })
    public ResponseEntity<List<MaterialConsumptionDto>> getConsumptionsByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate
    ) {
        List<MaterialConsumptionDto> consumptions = materialConsumptionService.getConsumptionsByDateRange(startDate, endDate);
        return ResponseEntity.ok(consumptions);
    }

    @GetMapping("/task/{taskId}")
    @PreAuthorize("hasAuthority('material-consumption:read') or hasAuthority('material-consumption:admin')")
    @Operation(
            summary = "List consumptions for a task",
            description = "Returns every consumption record linked to the given task."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Consumptions returned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the material-consumption read or admin authority"),
            @ApiResponse(responseCode = "404", description = "No task with the given id")
    })
    public ResponseEntity<List<MaterialConsumptionDto>> getConsumptionsByTask(@PathVariable Long taskId) {
        List<MaterialConsumptionDto> consumptions = materialConsumptionService.getConsumptionsByTask(taskId);
        return ResponseEntity.ok(consumptions);
    }
}
