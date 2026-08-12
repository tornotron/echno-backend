package org.tornotron.echno_backend.attendance;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping("/api/v1/shift-timings")
@Validated
public class ShiftTimingController {

    private final ShiftTimingService shiftTimingService;

    public ShiftTimingController(ShiftTimingService shiftTimingService) {
        this.shiftTimingService = shiftTimingService;
    }

    @PostMapping
    @PreAuthorize("@attendanceSecurity.canConfigureAttendance()")
    public ResponseEntity<ShiftTimingDto> create(@Valid @RequestBody ShiftTimingCreationDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(shiftTimingService.createShiftTiming(dto));
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<List<ShiftTimingDto>> getAll() {
        return ResponseEntity.ok(shiftTimingService.getAllShiftTimings());
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<ShiftTimingDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(shiftTimingService.getShiftTimingById(id));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("@attendanceSecurity.canConfigureAttendance()")
    public ResponseEntity<ShiftTimingDto> update(@PathVariable Long id,
                                                  @RequestBody ShiftTimingPatchDto dto) {
        return ResponseEntity.ok(shiftTimingService.updateShiftTiming(id, dto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@attendanceSecurity.canConfigureAttendance()")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        shiftTimingService.deleteShiftTiming(id);
        return ResponseEntity.noContent().build();
    }
}
