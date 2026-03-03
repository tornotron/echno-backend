package org.tornotron.echno_backend.attendance;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.attendance.dto.MovementRecordCreationDto;
import org.tornotron.echno_backend.attendance.dto.MovementRecordDto;
import org.tornotron.echno_backend.attendance.service.MovementRecordService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/movement-records/web")
@Validated
public class MovementRecordControllerWeb {

    private final MovementRecordService movementRecordService;

    public MovementRecordControllerWeb(MovementRecordService movementRecordService) {
        this.movementRecordService = movementRecordService;
    }

    @PostMapping
    public ResponseEntity<MovementRecordDto> create(
            @Valid @RequestBody MovementRecordCreationDto dto,
            @RequestParam Long employeeId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(movementRecordService.addMovement(dto, employeeId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<MovementRecordDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(movementRecordService.getMovementById(id));
    }

    @GetMapping("/attendance/{attendanceId}")
    public ResponseEntity<List<MovementRecordDto>> getByAttendance(@PathVariable Long attendanceId) {
        return ResponseEntity.ok(movementRecordService.getMovementsByAttendance(attendanceId));
    }

    @PostMapping("/{id}/verify")
    public ResponseEntity<MovementRecordDto> verify(@PathVariable Long id,
                                                     @RequestParam String verifiedBy) {
        return ResponseEntity.ok(movementRecordService.verifyMovement(id, verifiedBy));
    }
}
