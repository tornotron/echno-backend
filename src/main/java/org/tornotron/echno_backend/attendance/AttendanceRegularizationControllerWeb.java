package org.tornotron.echno_backend.attendance;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.attendance.dto.AttendanceRegularizationDto;
import org.tornotron.echno_backend.attendance.dto.RegularizationActionDto;
import org.tornotron.echno_backend.attendance.dto.RegularizationRequestDto;
import org.tornotron.echno_backend.attendance.service.AttendanceRegularizationService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/attendance-regularizations/web")
@Validated
public class AttendanceRegularizationControllerWeb {

    private final AttendanceRegularizationService regularizationService;

    public AttendanceRegularizationControllerWeb(AttendanceRegularizationService regularizationService) {
        this.regularizationService = regularizationService;
    }

    @PostMapping("/request")
    public ResponseEntity<AttendanceRegularizationDto> submitRequest(
            @Valid @RequestBody RegularizationRequestDto dto,
            @RequestParam String requestedBy) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(regularizationService.submitRequest(dto, requestedBy));
    }

    @PostMapping("/{id}/process")
    public ResponseEntity<AttendanceRegularizationDto> process(
            @PathVariable Long id,
            @Valid @RequestBody RegularizationActionDto dto,
            @RequestParam String approvedBy) {
        return ResponseEntity.ok(regularizationService.processRegularization(id, dto, approvedBy));
    }

    @GetMapping("/pending")
    public ResponseEntity<List<AttendanceRegularizationDto>> getPending() {
        return ResponseEntity.ok(regularizationService.getPendingRegularizations());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AttendanceRegularizationDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(regularizationService.getRegularizationById(id));
    }
}
