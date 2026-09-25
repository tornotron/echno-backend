package org.tornotron.echno_backend.attendance;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.attendance.dto.AttendanceRegularizationDto;
import org.tornotron.echno_backend.attendance.dto.RegularizationActionDto;
import org.tornotron.echno_backend.attendance.dto.RegularizationByDateRequestDto;
import org.tornotron.echno_backend.attendance.dto.RegularizationCalendarDayDto;
import org.tornotron.echno_backend.attendance.dto.RegularizationRequestDto;
import org.tornotron.echno_backend.attendance.enums.RegularizationStatus;
import org.tornotron.echno_backend.attendance.service.AttendanceRegularizationService;
import org.tornotron.echno_backend.attendance.service.RegularizationCalendarService;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;

import java.util.List;

@RestController
@RequestMapping("/api/v1/attendance-regularizations/web")
@Validated
@Tag(
        name = "Attendance Regularizations (Web)",
        description = "Requests to correct an attendance record that is missing clock events, for example "
                + "a forgotten evening clock-out, for the web client. An employee submits a request naming "
                + "the missing events and, optionally, the corrected events; a manager approves or rejects "
                + "it. Submitting and reading a request is tenant scoped, while listing pending requests "
                + "and processing one is limited to callers who can manage attendance records."
)
public class AttendanceRegularizationControllerWeb {

    private final AttendanceRegularizationService regularizationService;
    private final RegularizationCalendarService calendarService;

    public AttendanceRegularizationControllerWeb(AttendanceRegularizationService regularizationService,
            RegularizationCalendarService calendarService) {
        this.regularizationService = regularizationService;
        this.calendarService = calendarService;
    }

    @PostMapping("/request")
    // Ownership is settled in AttendanceRegularizationService against the stored attendance
    // record's employee: the payload names only an attendance id, which is the caller's word.
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Submit a regularization request",
            description = "Files a request to correct an attendance record, naming the missing clock "
                    + "events and, optionally, the events that should be added in their place. The "
                    + "authenticated caller is recorded as the requester, and cannot then approve the "
                    + "request themselves unless they hold the system-admin role."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Regularization request submitted"),
            @ApiResponse(responseCode = "400", description = "attendanceId, reason or missingEvents is missing or invalid"),
            @ApiResponse(responseCode = "403", description = "Caller is neither the employee the attendance record belongs to nor a holder of an attendance record-management role"),
            @ApiResponse(responseCode = "404", description = "No attendance record with the given attendanceId")
    })
    public ResponseEntity<AttendanceRegularizationDto> submitRequest(
            @Valid @RequestBody RegularizationRequestDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(regularizationService.submitRequest(dto));
    }

    @PostMapping("/request-by-date")
    // Ownership is settled in RegularizationCalendarService against the employee named in the
    // payload: the caller must be that employee or hold an attendance record-management role.
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Regularize a day by its date",
            description = "Files a regularization for a day named by employee, project and date, "
                    + "with the reason and the clock-in and clock-out times the employee asks for. "
                    + "When the employee has no attendance record for that day and project, one is "
                    + "created with no clock events and the status PENDING_REGULARIZATION, and the "
                    + "request is filed against it in the same step. The requested times reach the "
                    + "record only when the request is approved. Refused for a future date, a day "
                    + "on approved or pending leave, a day that already has a pending request, and "
                    + "a record that already has the requested clock events."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Regularization request submitted"),
            @ApiResponse(responseCode = "400", description = "A field is missing or invalid, or the day cannot be regularized for one of the reasons above"),
            @ApiResponse(responseCode = "403", description = "Caller is neither the employee named nor a holder of an attendance record-management role"),
            @ApiResponse(responseCode = "404", description = "No employee or project with the given id in this organization")
    })
    public ResponseEntity<AttendanceRegularizationDto> submitByDate(
            @Valid @RequestBody RegularizationByDateRequestDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(calendarService.submitByDate(dto));
    }

    @GetMapping("/calendar")
    @PreAuthorize("@attendanceSecurity.canViewEmployeeRecords(#employeeId)")
    @Operation(
            summary = "Regularization calendar for one month",
            description = "Returns every day of the month for one employee with what the day needs: "
                    + "complete, missing, incomplete, pending a regularization decision, on leave, a "
                    + "non-working day, or in the future, and whether the employee can act on it. "
                    + "Readable by the employee and by holders of an attendance record-management role."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One entry per day of the month, in date order"),
            @ApiResponse(responseCode = "400", description = "month is not between 1 and 12"),
            @ApiResponse(responseCode = "403", description = "Caller may not view this employee's attendance"),
            @ApiResponse(responseCode = "404", description = "No employee with the given id in this organization")
    })
    public ResponseEntity<List<RegularizationCalendarDayDto>> calendar(
            @RequestParam Long employeeId,
            @RequestParam int year,
            @RequestParam int month) {
        return ResponseEntity.ok(calendarService.calendar(employeeId, year, month));
    }

    @PostMapping("/{id}/process")
    @PreAuthorize("@attendanceSecurity.canManageRecords()")
    @Operation(
            summary = "Approve or reject a regularization request",
            description = "Sets the status of a pending regularization request. A rejection should carry "
                    + "a rejectionReason. The authenticated caller is recorded as the approver. Whoever "
                    + "raised the request cannot approve it: an approval is the second pair of eyes on a "
                    + "change to an attendance record, so it has to come from someone else. A system "
                    + "administrator is the one exception, and their self-approval is recorded as one on "
                    + "the corrected clock events. Rejecting your own request is allowed, since it writes "
                    + "nothing to the attendance record."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Regularization request processed"),
            @ApiResponse(responseCode = "400", description = "status is missing or invalid, the request has already been actioned, or it is being approved by whoever raised it without the system-admin role"),
            @ApiResponse(responseCode = "403", description = "Caller lacks permission to manage attendance records"),
            @ApiResponse(responseCode = "404", description = "No regularization request with the given id")
    })
    public ResponseEntity<AttendanceRegularizationDto> process(
            @PathVariable Long id,
            @Valid @RequestBody RegularizationActionDto dto) {
        return ResponseEntity.ok(regularizationService.processRegularization(id, dto));
    }

    @GetMapping("/pending")
    @PreAuthorize("@attendanceSecurity.canManageRecords()")
    @Operation(
            summary = "List pending regularization requests",
            description = "Returns the regularization requests awaiting approval or rejection, up to a "
                    + "fixed ceiling. The response carries X-Total-Count with the true number of pending "
                    + "requests, and X-Result-Capped when there were more than one response can hold. To "
                    + "page through the register, or to see requests that have already been decided, use "
                    + "the listing at the collection root instead."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pending regularization requests returned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks permission to manage attendance records")
    })
    public ResponseEntity<List<AttendanceRegularizationDto>> getPending() {
        return UnpagedResultCap.respond(regularizationService.getPendingRegularizations(
                0, UnpagedResultCap.MAX_ROWS));
    }

    @GetMapping
    @PreAuthorize("@attendanceSecurity.canManageRecords()")
    @Operation(
            summary = "List regularization requests",
            description = "Returns a paged list of regularization requests in the current tenant, in any "
                    + "status. The optional status, approver and requester parameters narrow the result; "
                    + "omitting a parameter leaves that dimension unfiltered.\n\n"
                    + "The approver and the rejecter are recorded in the same pair of columns, so "
                    + "approvedById on its own means \"requests this person decided\". Pair it with status "
                    + "to separate the two: approvedById with status=APPROVED is what that person "
                    + "approved, and with status=REJECTED what they rejected.\n\n"
                    + "Both ids are employee ids. A request decided by a caller who has no employee "
                    + "record in this tenant carries no approver id and is not matched by any "
                    + "approvedById."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of matching regularization requests"),
            @ApiResponse(responseCode = "400", description = "pageNo or pageSize is out of range"),
            @ApiResponse(responseCode = "403", description = "Caller lacks permission to manage attendance records")
    })
    public Page<AttendanceRegularizationDto> list(
            @RequestParam(required = false) RegularizationStatus status,
            @RequestParam(required = false) Long approvedById,
            @RequestParam(required = false) Long requestedById,
            PageQuery pageQuery) {
        return regularizationService.findAll(status, approvedById, requestedById,
                pageQuery.getPageNo(), pageQuery.getPageSize());
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Get a regularization request by id",
            description = "Returns a single regularization request including its approval state."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Regularization request found"),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant"),
            @ApiResponse(responseCode = "404", description = "No regularization request with the given id")
    })
    public ResponseEntity<AttendanceRegularizationDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(regularizationService.getRegularizationById(id));
    }
}
