package org.tornotron.echno_backend.attendance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.attendance.dto.*;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;
import org.tornotron.echno_backend.common.response.ApiResponse;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/attendance/web")
@Validated
public class AttendanceControllerWeb {

    private final AttendanceService attendanceService;
    private final ObjectMapper objectMapper;

    public AttendanceControllerWeb(AttendanceService attendanceService, ObjectMapper objectMapper) {
        this.attendanceService = attendanceService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/check-in",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AttendanceResponseDto> checkIn(@RequestParam("data") @Valid String data,
                                                         @RequestParam(value = "photo", required = false)MultipartFile photo) throws JsonProcessingException {
        AttendanceCheckInDto dto = objectMapper.readValue(data, AttendanceCheckInDto.class);
        return ResponseEntity.status(HttpStatus.CREATED).body(attendanceService.checkIn(dto,photo));
    }

    @PostMapping(value = "/clock-event",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AttendanceResponseDto> recordClockEvent(@RequestParam("data") @Valid String data,
                                                                    @RequestParam(value = "photo", required = false) MultipartFile photo) throws JsonProcessingException {
        AttendanceClockEventDto dto = objectMapper.readValue(data, AttendanceClockEventDto.class);
        return ResponseEntity.ok(attendanceService.recordClockEvent(dto,photo));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AttendanceResponseDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(attendanceService.getAttendanceById(id));
    }

    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<List<AttendanceResponseDto>> getByEmployee(
            @PathVariable Long employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(attendanceService.getAttendanceByEmployee(employeeId, startDate, endDate));
    }

    @GetMapping("/project/{projectId}")
    public ResponseEntity<List<AttendanceResponseDto>> getByProject(
            @PathVariable Long projectId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) AttendanceStatus status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(attendanceService.getAttendanceByProject(
                projectId, date, status, search,
                PageRequest.of(page, size, Sort.by("employeeName"))));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<AttendanceResponseDto> approve(
            @PathVariable Long id,
            @Valid @RequestBody AttendanceApprovalDto dto) {
        return ResponseEntity.ok(attendanceService.approveAttendance(id, dto, "system"));
    }

    @PostMapping("/mark-absent")
    public ResponseEntity<AttendanceResponseDto> markAbsent(
            @RequestParam Long employeeId,
            @RequestParam Long projectId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(attendanceService.markAbsent(employeeId, projectId, date));
    }

    @GetMapping("/summary/{employeeId}")
    public ResponseEntity<AttendanceSummaryDto> getMonthlySummary(
            @PathVariable Long employeeId,
            @RequestParam int month,
            @RequestParam int year) {
        return ResponseEntity.ok(attendanceService.getMonthlySummary(employeeId, month, year));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse> delete(@PathVariable Long id) {
        attendanceService.deleteAttendance(id);
        return ResponseEntity.ok(new ApiResponse("Attendance record deleted successfully"));
    }
}
