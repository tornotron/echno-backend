package org.tornotron.echno_backend.attendance;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.attendance.dto.AttendanceRecordDto;

@RestController
@RequestMapping("/attendance")
@Validated
public class AttendanceController {

    private final AttendanceService service;

    public AttendanceController(AttendanceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<String> createAttendance(@Valid @RequestBody AttendanceRecordDto attendanceRecordDto) {
        service.recordAttendance(attendanceRecordDto);
        return ResponseEntity.status(HttpStatus.CREATED).body("Attendance was marked successfully");
    }
}
