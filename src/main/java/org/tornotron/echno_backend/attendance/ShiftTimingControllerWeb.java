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
import org.tornotron.echno_backend.attendance.dto.ShiftTimingCreationDto;
import org.tornotron.echno_backend.attendance.dto.ShiftTimingDto;
import org.tornotron.echno_backend.attendance.dto.ShiftTimingPatchDto;
import org.tornotron.echno_backend.attendance.service.ShiftTimingService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/shift-timings/web")
@Validated
@Tag(
        name = "Shift Timings (Web)",
        description = "Named work shifts (start and end time, lunch break window, grace period, and the "
                + "minimum, half-day and overtime work-hour thresholds) that attendance records are "
                + "evaluated against, for the web client. Reading shift timings is tenant scoped; creating, "
                + "updating and deleting them is limited to callers who can configure attendance."
)
public class ShiftTimingControllerWeb {

    private final ShiftTimingService shiftTimingService;

    public ShiftTimingControllerWeb(ShiftTimingService shiftTimingService) {
        this.shiftTimingService = shiftTimingService;
    }

    @PostMapping
    @PreAuthorize("@attendanceSecurity.canConfigureAttendance()")
    @Operation(
            summary = "Create a shift timing",
            description = "Creates a named shift with its start and end time, lunch break window, grace "
                    + "period and work-hour thresholds."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Shift timing created"),
            @ApiResponse(responseCode = "400", description = "A required field is missing or out of range"),
            @ApiResponse(responseCode = "403", description = "Caller lacks permission to configure attendance settings")
    })
    public ResponseEntity<ShiftTimingDto> create(@Valid @RequestBody ShiftTimingCreationDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(shiftTimingService.createShiftTiming(dto));
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "List shift timings",
            description = "Returns every shift timing defined in the current tenant."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Shift timings returned"),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant")
    })
    public ResponseEntity<List<ShiftTimingDto>> getAll() {
        return ResponseEntity.ok(shiftTimingService.getAllShiftTimings());
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Get a shift timing by id",
            description = "Returns a single shift timing."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Shift timing found"),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant"),
            @ApiResponse(responseCode = "404", description = "No shift timing with the given id")
    })
    public ResponseEntity<ShiftTimingDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(shiftTimingService.getShiftTimingById(id));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("@attendanceSecurity.canConfigureAttendance()")
    @Operation(
            summary = "Update a shift timing",
            description = "Applies a partial update to a shift timing. Only the fields present in the "
                    + "request body are changed."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Shift timing updated"),
            @ApiResponse(responseCode = "400", description = "A field failed validation"),
            @ApiResponse(responseCode = "403", description = "Caller lacks permission to configure attendance settings"),
            @ApiResponse(responseCode = "404", description = "No shift timing with the given id")
    })
    public ResponseEntity<ShiftTimingDto> update(@PathVariable Long id,
                                                  @RequestBody ShiftTimingPatchDto dto) {
        return ResponseEntity.ok(shiftTimingService.updateShiftTiming(id, dto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@attendanceSecurity.canConfigureAttendance()")
    @Operation(
            summary = "Delete a shift timing",
            description = "Deletes the shift timing with the given id."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Shift timing deleted"),
            @ApiResponse(responseCode = "403", description = "Caller lacks permission to configure attendance settings"),
            @ApiResponse(responseCode = "404", description = "No shift timing with the given id")
    })
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        shiftTimingService.deleteShiftTiming(id);
        return ResponseEntity.noContent().build();
    }
}
