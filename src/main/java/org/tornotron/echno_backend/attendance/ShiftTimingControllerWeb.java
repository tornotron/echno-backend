package org.tornotron.echno_backend.attendance;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.attendance.dto.ShiftTimingCreationDto;
import org.tornotron.echno_backend.attendance.dto.ShiftTimingDto;
import org.tornotron.echno_backend.attendance.dto.ShiftTimingPatchDto;
import org.tornotron.echno_backend.attendance.service.ShiftTimingService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/shift-timings/web")
@Validated
public class ShiftTimingControllerWeb {

    private final ShiftTimingService shiftTimingService;

    public ShiftTimingControllerWeb(ShiftTimingService shiftTimingService) {
        this.shiftTimingService = shiftTimingService;
    }

    @PostMapping
    public ResponseEntity<ShiftTimingDto> create(@Valid @RequestBody ShiftTimingCreationDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(shiftTimingService.createShiftTiming(dto));
    }

    @GetMapping
    public ResponseEntity<List<ShiftTimingDto>> getAll() {
        return ResponseEntity.ok(shiftTimingService.getAllShiftTimings());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ShiftTimingDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(shiftTimingService.getShiftTimingById(id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ShiftTimingDto> update(@PathVariable Long id,
                                                  @RequestBody ShiftTimingPatchDto dto) {
        return ResponseEntity.ok(shiftTimingService.updateShiftTiming(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        shiftTimingService.deleteShiftTiming(id);
        return ResponseEntity.noContent().build();
    }
}
