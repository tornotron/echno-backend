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
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;
import org.tornotron.echno_backend.common.response.ApiResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
    // Ownership is settled in AttendanceService against the employee the payload names:
    // the employee id travels inside the multipart data part, where this guard cannot read it.
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither the employee named nor a holder of an attendance record-management role")
    })
    public ResponseEntity<AttendanceResponseDto> checkIn(
            @Parameter(schema = @Schema(implementation = AttendanceCheckInDto.class))
            @RequestParam("data") String data,
            @RequestParam(value = "photo", required = false)MultipartFile photo) throws JsonProcessingException {
        AttendanceCheckInDto dto = jsonPartBinder.read(data, AttendanceCheckInDto.class);
        return ResponseEntity.status(HttpStatus.CREATED).body(attendanceService.checkIn(dto,photo));
    }

    @PostMapping(value = "/clock-event",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    // Ownership is settled in AttendanceService against the stored attendance record's
    // employee: the payload names only an attendance id, which is the caller's word.
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither the employee the record belongs to nor a holder of an attendance record-management role"),
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
                    + "regularizations and approval state. Readable by the employee it belongs to, "
                    + "by a holder of an attendance record-management role, or by the approver the "
                    + "record names."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Attendance record found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither the employee the record belongs to, nor a holder of an attendance record-management role, nor the approver the record names"),
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

    // Tenant membership is all the annotation can check, because who may decide this record is a
    // column on the record itself: a geofence exception names the employee's reporting manager,
    // who is usually not one of the organization-wide record-management roles. Reading an approver
    // id off the request would let a caller nominate themselves, so the real check runs in the
    // service against the stored record, the way the check-in and clock-event guards already do.
    @PostMapping("/{id}/approve")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Approve or reject an attendance record",
            description = "Sets the approval status of an attendance record, with an optional remark, "
                    + "typically after a regularization request has been reviewed."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Approval status updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "approvalStatus is missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither an attendance record manager nor the approver the record names, or is the employee whose own geofence exception it is"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No attendance record with the given id")
    })
    public ResponseEntity<AttendanceResponseDto> approve(
            @PathVariable Long id,
            @Valid @RequestBody AttendanceApprovalDto dto) {
        return ResponseEntity.ok(attendanceService.approveAttendance(id, dto));
    }

    // The queue is the caller's own and takes no parameters. Reading the approver from a query
    // parameter under a guard that only asks for a role is the shape #683 took off the leave
    // queue: the guard checks a role, the query reads a number the caller chose, and an
    // administrator ends up able to read a colleague's queue while the approvers a chain is
    // actually built from can read none of their own. The caller is resolved from the session in
    // the service, so a caller that still sends approverId is served their own queue, a query
    // parameter no handler declares being ignored.
    @GetMapping("/pending-approvals")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "List the attendance days waiting on you",
            description = "Returns the attendance days held for a geofence decision that the signed-in "
                    + "caller may decide: the days that name them as approver, and, for a holder of the "
                    + "attendance record-management roles, every other held day in the tenant, which is "
                    + "how the days no approver could be resolved for are reached. A caller's own days "
                    + "are never in it, because nobody approves their own absence from site whatever "
                    + "roles they hold. Newest day first, up to a fixed ceiling; the response carries "
                    + "X-Total-Count with the true number waiting, and X-Result-Capped when there were "
                    + "more than one response can hold."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Records waiting on the caller returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant, or has no employee record in it, so there is no queue to serve")
    })
    public ResponseEntity<List<AttendanceResponseDto>> getPendingApprovals() {
        return UnpagedResultCap.respond(
                attendanceService.getPendingApprovals(0, UnpagedResultCap.MAX_ROWS));
    }

    @GetMapping("/pending-approvals/count")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Count the attendance days waiting on you",
            description = "Returns how many attendance days are awaiting a decision from the signed-in "
                    + "caller, for the badge a client draws on the menu. Counted over the same set the "
                    + "listing serves."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Count returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant, or has no employee record in it, so there is no queue to count")
    })
    public ResponseEntity<Map<String, Long>> getPendingApprovalCount() {
        return ResponseEntity.ok(Map.of("count", attendanceService.getPendingApprovalCount()));
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
