package org.tornotron.echno_backend.materialConsumption;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.materialConsumption.dto.MaterialConsumptionCreationDto;
import org.tornotron.echno_backend.materialConsumption.dto.MaterialConsumptionDto;
import org.tornotron.echno_backend.materialConsumption.enums.MaterialConsumptionType;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/material-consumptions/web")
@Validated
public class MaterialConsumptionControllerWeb {

    private final MaterialConsumptionService materialConsumptionService;

    public MaterialConsumptionControllerWeb(MaterialConsumptionService materialConsumptionService) {
        this.materialConsumptionService = materialConsumptionService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('material-consumption:create') or hasAuthority('material-consumption:admin')")
    public ResponseEntity<MaterialConsumptionDto> createMaterialConsumption(
            @Valid @RequestBody MaterialConsumptionCreationDto creationDto) {
        MaterialConsumptionDto created = materialConsumptionService.createMaterialConsumption(creationDto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('material-consumption:read') or hasAuthority('material-consumption:admin')")
    public ResponseEntity<MaterialConsumptionDto> getMaterialConsumptionById(@PathVariable Long id) {
        MaterialConsumptionDto consumption = materialConsumptionService.getMaterialConsumptionById(id);
        return ResponseEntity.ok(consumption);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('material-consumption:read') or hasAuthority('material-consumption:admin')")
    public ResponseEntity<List<MaterialConsumptionDto>> getAllMaterialConsumptions() {
        List<MaterialConsumptionDto> consumptions = materialConsumptionService.getAllMaterialConsumptions();
        return ResponseEntity.ok(consumptions);
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('material-consumption:read') or hasAuthority('material-consumption:admin')")
    public ResponseEntity<Page<MaterialConsumptionDto>> getAllMaterialConsumptionsPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        Page<MaterialConsumptionDto> consumptions = materialConsumptionService.getAllMaterialConsumptions(pageNo, pageSize);
        return ResponseEntity.ok(consumptions);
    }

    @GetMapping("/material/{materialId}")
    @PreAuthorize("hasAuthority('material-consumption:read') or hasAuthority('material-consumption:admin')")
    public ResponseEntity<List<MaterialConsumptionDto>> getConsumptionsByMaterial(@PathVariable Long materialId) {
        List<MaterialConsumptionDto> consumptions = materialConsumptionService.getConsumptionsByMaterial(materialId);
        return ResponseEntity.ok(consumptions);
    }

    @GetMapping("/type/{consumptionType}")
    @PreAuthorize("hasAuthority('material-consumption:read') or hasAuthority('material-consumption:admin')")
    public ResponseEntity<List<MaterialConsumptionDto>> getConsumptionsByType(@PathVariable MaterialConsumptionType consumptionType) {
        List<MaterialConsumptionDto> consumptions = materialConsumptionService.getConsumptionsByType(consumptionType);
        return ResponseEntity.ok(consumptions);
    }

    @GetMapping("/date-range")
    @PreAuthorize("hasAuthority('material-consumption:read') or hasAuthority('material-consumption:admin')")
    public ResponseEntity<List<MaterialConsumptionDto>> getConsumptionsByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate
    ) {
        List<MaterialConsumptionDto> consumptions = materialConsumptionService.getConsumptionsByDateRange(startDate, endDate);
        return ResponseEntity.ok(consumptions);
    }
}
