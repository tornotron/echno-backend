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
import org.tornotron.echno_backend.attendance.dto.RegularizationRequestDto;
import org.tornotron.echno_backend.attendance.service.AttendanceRegularizationService;

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

    public AttendanceRegularizationControllerWeb(AttendanceRegularizationService regularizationService) {
        this.regularizationService = regularizationService;
    }

    @PostMapping("/request")
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
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant"),
            @ApiResponse(responseCode = "404", description = "No attendance record with the given attendanceId")
    })
    public ResponseEntity<AttendanceRegularizationDto> submitRequest(
            @Valid @RequestBody RegularizationRequestDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(regularizationService.submitRequest(dto));
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
            description = "Returns every regularization request awaiting approval or rejection."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pending regularization requests returned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks permission to manage attendance records")
    })
    public ResponseEntity<List<AttendanceRegularizationDto>> getPending() {
        return ResponseEntity.ok(regularizationService.getPendingRegularizations());
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
