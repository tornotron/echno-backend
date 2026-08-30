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
import org.tornotron.echno_backend.attendance.dto.MovementRecordCreationDto;
import org.tornotron.echno_backend.attendance.dto.MovementRecordDto;
import org.tornotron.echno_backend.attendance.service.MovementRecordService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/movement-records")
@Validated
@Tag(
        name = "Movement Records",
        description = "Records of an employee travelling away from their checked-in location during a "
                + "work day, for example a site visit, client meeting or material procurement trip. Each "
                + "movement is logged against an attendance record with a start and end time, distance and "
                + "purpose, and can be verified afterwards. Creating and reading movements is tenant "
                + "scoped; verifying one is limited to callers who can manage attendance records."
)
public class MovementRecordController {

    private final MovementRecordService movementRecordService;

    public MovementRecordController(MovementRecordService movementRecordService) {
        this.movementRecordService = movementRecordService;
    }

    @PostMapping
    // Ownership is settled in MovementRecordService against both the employee named and the
    // stored attendance record's employee: ids on the request are the caller's word.
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Log a movement",
            description = "Records an employee's movement away from their checked-in location against an "
                    + "existing attendance record."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Movement record created"),
            @ApiResponse(responseCode = "400", description = "attendanceId, movementType, fromLocation, startTime or purpose is missing or invalid"),
            @ApiResponse(responseCode = "403", description = "Caller is neither the employee involved nor a holder of an attendance record-management role"),
            @ApiResponse(responseCode = "404", description = "No attendance record or employee with the given id")
    })
    public ResponseEntity<MovementRecordDto> create(
            @Valid @RequestBody MovementRecordCreationDto dto,
            @RequestParam Long employeeId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(movementRecordService.addMovement(dto, employeeId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Get a movement record by id",
            description = "Returns a single movement record including its verification state."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Movement record found"),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant"),
            @ApiResponse(responseCode = "404", description = "No movement record with the given id")
    })
    public ResponseEntity<MovementRecordDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(movementRecordService.getMovementById(id));
    }

    @GetMapping("/attendance/{attendanceId}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "List movements for an attendance record",
            description = "Returns every movement logged against a given attendance record."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Movement records returned"),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant"),
            @ApiResponse(responseCode = "404", description = "No attendance record with the given id")
    })
    public ResponseEntity<List<MovementRecordDto>> getByAttendance(@PathVariable Long attendanceId) {
        return ResponseEntity.ok(movementRecordService.getMovementsByAttendance(attendanceId));
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize("@attendanceSecurity.canManageRecords()")
    @Operation(
            summary = "Verify a movement record",
            description = "Marks a movement record as verified by the given approver, for example after "
                    + "checking the reported distance and purpose."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Movement record verified"),
            @ApiResponse(responseCode = "403", description = "Caller lacks permission to manage attendance records"),
            @ApiResponse(responseCode = "404", description = "No movement record with the given id")
    })
    public ResponseEntity<MovementRecordDto> verify(@PathVariable Long id,
                                                     @RequestParam String verifiedBy) {
        return ResponseEntity.ok(movementRecordService.verifyMovement(id, verifiedBy));
    }
}
