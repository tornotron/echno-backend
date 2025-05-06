package org.tornotron.echno_backend.attendance;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.attendance.dto.AttendanceCreationDto;
import org.tornotron.echno_backend.common.response.ApiResponse;

@RestController
@RequestMapping("/api/v1/attendance")
@Validated
public class AttendanceController {

    private final AttendanceService service;

    public AttendanceController(AttendanceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse> createAttendance(@Valid @RequestBody AttendanceCreationDto attendanceCreationDto) {
        service.recordAttendance(attendanceCreationDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse("Attendance Recorded Successfully"));
    }
}
