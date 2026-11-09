package org.tornotron.echno_backend.attendance;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.tornotron.echno_backend.attendance.service.AttendanceRegularizationService;
import org.tornotron.echno_backend.attendance.service.RegularizationCalendarService;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springdoc.core.annotations.ParameterObject;
import org.tornotron.echno_backend.common.pagination.PageQuery;

@RestController
@RequestMapping("/api/v1/attendance-regularizations")
@Validated
@Tag(
        name = "Attendance Regularizations",
        description = "Requests to correct an attendance record that is missing clock events, for example "
                + "a forgotten evening clock-out. An employee submits a request naming the missing events "
                + "and, optionally, the corrected events; a manager approves or rejects it. Submitting and "
                + "reading a request is tenant scoped, while listing pending requests and processing one is "
                + "limited to callers who can manage attendance records."
)
public class AttendanceRegularizationController {

    private final AttendanceRegularizationService regularizationService;
    private final RegularizationCalendarService calendarService;

    public AttendanceRegularizationController(AttendanceRegularizationService regularizationService,
            RegularizationCalendarService calendarService) {
        this.regularizationService = regularizationService;
        this.calendarService = calendarService;
    }

    @PostMapping("/request")
    // Ownership is settled in AttendanceRegularizationService against the stored attendance
    // record's employee: the payload names only an attendance id, which is the caller's word.
    @PreAuthorize("@access.can('leave-regularisation', 'create')")
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
            @ApiResponse(responseCode = "403", description = "Caller may not raise a regularization on this attendance record: it is not their own, and they may not raise them for every employee"),
            @ApiResponse(responseCode = "404", description = "No attendance record with the given attendanceId")
    })
    public ResponseEntity<AttendanceRegularizationDto> submitRequest(
            @Valid @RequestBody RegularizationRequestDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(regularizationService.submitRequest(dto));
    }

    @PostMapping("/request-by-date")
    // Ownership is settled in RegularizationCalendarService against the employee named in the
    // payload: the caller must be that employee or raise regularizations for every employee.
    @PreAuthorize("@access.can('leave-regularisation', 'create')")
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
            @ApiResponse(responseCode = "403", description = "Caller may not raise a regularization for this employee: it is not their own attendance, and they may not raise them for every employee"),
            @ApiResponse(responseCode = "404", description = "No employee or project with the given id in this organization")
    })
    public ResponseEntity<AttendanceRegularizationDto> submitByDate(
            @Valid @RequestBody RegularizationByDateRequestDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(calendarService.submitByDate(dto));
    }

    @GetMapping("/calendar")
    @PreAuthorize("@access.can('leave-regularisation', 'read')")
    @Operation(
            summary = "Regularization calendar for one month",
            description = "Returns every day of the month for one employee with what the day needs: "
                    + "complete, missing, incomplete, pending a regularization decision, on leave, a "
                    + "non-working day, or in the future, and whether the employee can act on it. "
                    + "Readable by the employee and by anyone who reads regularizations across the organization."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One entry per day of the month, in date order"),
            @ApiResponse(responseCode = "400", description = "month is not between 1 and 12"),
            @ApiResponse(responseCode = "403", description = "Caller may not read this employee's regularizations"),
            @ApiResponse(responseCode = "404", description = "No employee with the given id in this organization")
    })
    public ResponseEntity<List<RegularizationCalendarDayDto>> calendar(
            @RequestParam Long employeeId,
            @RequestParam int year,
            @RequestParam int month) {
        return ResponseEntity.ok(calendarService.calendar(employeeId, year, month));
    }

    @PostMapping("/{id}/process")
    @PreAuthorize("@access.can('leave-regularisation', 'edit')")
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
            @ApiResponse(responseCode = "403", description = "Caller may not decide regularization requests"),
            @ApiResponse(responseCode = "404", description = "No regularization request with the given id")
    })
    public ResponseEntity<AttendanceRegularizationDto> process(
            @PathVariable Long id,
            @Valid @RequestBody RegularizationActionDto dto) {
        return ResponseEntity.ok(regularizationService.processRegularization(id, dto));
    }

    @GetMapping("/pending")
    @PreAuthorize("@access.can('leave-regularisation', 'read')")
    @Operation(
            summary = "List pending regularization requests",
            description = "Returns the regularization requests awaiting approval or rejection, one page "
                    + "at a time, latest request first. totalElements is the number pending."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pending regularization requests returned"),
            @ApiResponse(responseCode = "403", description = "Caller may not read every employee's regularization requests")
    })
    public ResponseEntity<Page<AttendanceRegularizationDto>> getPending(
            @Valid @ParameterObject PageQuery pageQuery) {
        return ResponseEntity.ok(regularizationService.getPendingRegularizations(
                pageQuery.getPageNo(), pageQuery.getPageSize()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@access.can('leave-regularisation', 'read')")
    @Operation(
            summary = "Get a regularization request by id",
            description = "Returns a single regularization request including its approval state. Readable by the employee whose attendance it corrects, by whoever decided it, and by anyone who reads regularizations across the organization."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Regularization request found"),
            @ApiResponse(responseCode = "403", description = "Caller is not the employee the request is for, not whoever decided it, and may not read every employee's regularization requests"),
            @ApiResponse(responseCode = "404", description = "No regularization request with the given id")
    })
    public ResponseEntity<AttendanceRegularizationDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(regularizationService.getRegularizationById(id));
    }
}
