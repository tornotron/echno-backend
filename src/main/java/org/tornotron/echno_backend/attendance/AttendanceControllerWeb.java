package org.tornotron.echno_backend.attendance;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.tornotron.echno_backend.common.payload.JsonPartBinder;
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
@Tag(
        name = "Attendance (Web)",
        description = "Daily attendance records built from clock events across a shift's sessions, for the "
                + "web client. Endpoints cover checking in, recording clock events, reading and browsing "
                + "records, approving or marking an employee absent, and monthly summaries. Access is "
                + "scoped to the caller's tenant, with record-level checks gating who can view an "
                + "employee's history or manage attendance for a project."
)
public class AttendanceControllerWeb {

    private final AttendanceService attendanceService;
    private final JsonPartBinder jsonPartBinder;

    public AttendanceControllerWeb(AttendanceService attendanceService, JsonPartBinder jsonPartBinder) {
        this.attendanceService = attendanceService;
        this.jsonPartBinder = jsonPartBinder;
    }

    @PostMapping(value = "/check-in",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Check in for the day",
            description = "Records the first clock event of the day for an employee against a project and "
                    + "shift, from a multipart request carrying the check-in details as JSON and an optional "
                    + "photo."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Check-in recorded"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The data part is not valid check-in JSON, or a field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant")
    })
    public ResponseEntity<AttendanceResponseDto> checkIn(
            @Parameter(schema = @Schema(implementation = AttendanceCheckInDto.class))
            @RequestParam("data") String data,
            @RequestParam(value = "photo", required = false)MultipartFile photo) throws JsonProcessingException {
        AttendanceCheckInDto dto = jsonPartBinder.read(data, AttendanceCheckInDto.class);
        return ResponseEntity.status(HttpStatus.CREATED).body(attendanceService.checkIn(dto,photo));
    }

    @PostMapping(value = "/clock-event",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Record a clock event",
            description = "Adds a clock event, such as lunch break start or evening clock-out, to an "
                    + "existing attendance record, from a multipart request carrying the event details as "
                    + "JSON and an optional photo."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Clock event recorded"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The data part is not valid clock event JSON, or a field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No attendance record with the given id")
    })
    public ResponseEntity<AttendanceResponseDto> recordClockEvent(
            @Parameter(schema = @Schema(implementation = AttendanceClockEventDto.class))
            @RequestParam("data") String data,
            @RequestParam(value = "photo", required = false) MultipartFile photo) throws JsonProcessingException {
        AttendanceClockEventDto dto = jsonPartBinder.read(data, AttendanceClockEventDto.class);
        return ResponseEntity.ok(attendanceService.recordClockEvent(dto,photo));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Get an attendance record by id",
            description = "Returns a single attendance record including its clock events, movements, "
                    + "regularizations and approval state."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Attendance record found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No attendance record with the given id")
    })
    public ResponseEntity<AttendanceResponseDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(attendanceService.getAttendanceById(id));
    }

    @GetMapping("/employee/{employeeId}")
    @PreAuthorize("@attendanceSecurity.canViewEmployeeRecords(#employeeId)")
    @Operation(
            summary = "List an employee's attendance in a date range",
            description = "Returns the attendance records for one employee between startDate and endDate, "
                    + "inclusive."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Attendance records returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks permission to view this employee's attendance records"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<List<AttendanceResponseDto>> getByEmployee(
            @PathVariable Long employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(attendanceService.getAttendanceByEmployee(employeeId, startDate, endDate));
    }

    @GetMapping("/project/{projectId}")
    @PreAuthorize("@attendanceSecurity.canManageRecords()")
    @Operation(
            summary = "List a project's attendance for a date",
            description = "Returns a page of attendance records for a project on a given date, optionally "
                    + "filtered by status or a search term against the employee name, sorted by employee "
                    + "name."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Attendance records returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks permission to manage attendance records"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No project with the given id")
    })
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
    @PreAuthorize("@attendanceSecurity.canManageRecords()")
    @Operation(
            summary = "Approve or reject an attendance record",
            description = "Sets the approval status of an attendance record, with an optional remark, "
                    + "typically after a regularization request has been reviewed."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Approval status updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "approvalStatus is missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks permission to manage attendance records"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No attendance record with the given id")
    })
    public ResponseEntity<AttendanceResponseDto> approve(
            @PathVariable Long id,
            @Valid @RequestBody AttendanceApprovalDto dto) {
        return ResponseEntity.ok(attendanceService.approveAttendance(id, dto));
    }

    @PostMapping("/mark-absent")
    @PreAuthorize("@attendanceSecurity.canManageRecords()")
    @Operation(
            summary = "Mark an employee absent",
            description = "Creates or updates the attendance record for an employee on a project for the "
                    + "given date with an absent status, for use when no clock event was recorded."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employee marked absent"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks permission to manage attendance records"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee or project with the given id")
    })
    public ResponseEntity<AttendanceResponseDto> markAbsent(
            @RequestParam Long employeeId,
            @RequestParam Long projectId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(attendanceService.markAbsent(employeeId, projectId, date));
    }

    @GetMapping("/summary/{employeeId}")
    @PreAuthorize("@attendanceSecurity.canViewEmployeeRecords(#employeeId)")
    @Operation(
            summary = "Get an employee's monthly attendance summary",
            description = "Returns aggregated counts (present, absent, half days, leave, overtime and so "
                    + "on) and computed totals for one employee over a calendar month."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Monthly summary returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks permission to view this employee's attendance records"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<AttendanceSummaryDto> getMonthlySummary(
            @PathVariable Long employeeId,
            @RequestParam int month,
            @RequestParam int year) {
        return ResponseEntity.ok(attendanceService.getMonthlySummary(employeeId, month, year));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@attendanceSecurity.canManageRecords()")
    @Operation(
            summary = "Delete an attendance record",
            description = "Deletes the attendance record with the given id, along with its clock events "
                    + "and movements."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Attendance record deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks permission to manage attendance records"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No attendance record with the given id")
    })
    public ResponseEntity<ApiResponse> delete(@PathVariable Long id) {
        attendanceService.deleteAttendance(id);
        return ResponseEntity.ok(new ApiResponse("Attendance record deleted successfully"));
    }
}
