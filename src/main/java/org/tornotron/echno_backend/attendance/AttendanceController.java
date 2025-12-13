package org.tornotron.echno_backend.attendance;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.attendance.dto.*;
import org.tornotron.echno_backend.common.response.ApiResponse;

import java.time.LocalDate;
import java.util.List;

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

    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<List<AttendanceResponseDto>> getAttendanceByEmployee(
            @PathVariable Long employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        List<AttendanceResponseDto> records = service.getAttendanceByEmployee(employeeId, startDate, endDate);
        return ResponseEntity.ok(records);
    }

    @GetMapping("/employee/name/{employeeName}")
    public ResponseEntity<List<AttendanceResponseDto>> getAttendanceByEmployeeName(
            @PathVariable String employeeName,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        List<AttendanceResponseDto> records = service.getAttendanceByEmployeeName(employeeName, startDate, endDate);
        return ResponseEntity.ok(records);
    }

    @GetMapping("/summary/daily/{employeeId}")
    public ResponseEntity<AttendanceSummaryDto> getDailySummary(
            @PathVariable Long employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        AttendanceSummaryDto summary = service.getDailySummary(employeeId, date);
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/summary/range/{employeeId}")
    public ResponseEntity<List<AttendanceSummaryDto>> getSummaryForDateRange(
            @PathVariable Long employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        List<AttendanceSummaryDto> summaries = service.getSummaryForDateRange(employeeId, startDate, endDate);
        return ResponseEntity.ok(summaries);
    }

    @PutMapping("/update")
    public ResponseEntity<AttendanceResponseDto> updateAttendance(@Valid @RequestBody AttendanceUpdateDto updateDto) {
        AttendanceResponseDto updated = service.updateAttendance(updateDto);
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/corrected")
    public ResponseEntity<List<AttendanceResponseDto>> getCorrectedRecords(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        List<AttendanceResponseDto> correctedRecords = service.getCorrectedRecords(startDate, endDate);
        return ResponseEntity.ok(correctedRecords);
    }

    @GetMapping("/corrected/employee/{employeeId}")
    public ResponseEntity<List<AttendanceResponseDto>> getCorrectedRecordsByEmployee(@PathVariable Long employeeId) {
        List<AttendanceResponseDto> correctedRecords = service.getCorrectedRecordsByEmployee(employeeId);
        return ResponseEntity.ok(correctedRecords);
    }

    @DeleteMapping("/{attendanceId}")
    public ResponseEntity<ApiResponse> deleteAttendance(@PathVariable Long attendanceId) {
        service.deleteAttendance(attendanceId);
        return ResponseEntity.ok(new ApiResponse("Attendance record deleted successfully"));
    }
}
