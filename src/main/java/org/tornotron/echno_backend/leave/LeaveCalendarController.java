package org.tornotron.echno_backend.leave;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.leave.dto.LeaveCalendarDto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/leave-calendar")
@Validated
@Tag(
        name = "Leave Calendar",
        description = "Day-by-day view of who is on leave, built from approved leave requests. Endpoints "
                + "cover the whole organization, a single department, a single employee or a manager's "
                + "team, over a given date range, plus a grouped-by-date view and a headcount for a single "
                + "day. Access is gated by the leave read or admin authority."
)
public class LeaveCalendarController {

    private final LeaveCalendarService calendarService;

    public LeaveCalendarController(LeaveCalendarService calendarService) {
        this.calendarService = calendarService;
    }

    @GetMapping("/organization/{organizationId}")
    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    @Operation(
            summary = "Get an organization's leave calendar",
            description = "Returns every leave calendar entry for the organization between startDate and "
                    + "endDate, inclusive."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Calendar entries returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the leave read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No organization with the given id")
    })
    public ResponseEntity<List<LeaveCalendarDto>> getOrganizationCalendar(
            @PathVariable Long organizationId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getCalendarByOrganization(organizationId, startDate, endDate));
    }

    @GetMapping("/organization/{organizationId}/department")
    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    @Operation(
            summary = "Get a department's leave calendar",
            description = "Returns every leave calendar entry for the named department within the "
                    + "organization between startDate and endDate, inclusive."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Calendar entries returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the leave read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No organization with the given id")
    })
    public ResponseEntity<List<LeaveCalendarDto>> getDepartmentCalendar(
            @PathVariable Long organizationId,
            @RequestParam String department,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getCalendarByDepartment(organizationId, department, startDate, endDate));
    }

    @GetMapping("/employee/{employeeId}")
    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    @Operation(
            summary = "Get an employee's leave calendar",
            description = "Returns every leave calendar entry for the employee between startDate and "
                    + "endDate, inclusive."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Calendar entries returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the leave read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<List<LeaveCalendarDto>> getEmployeeCalendar(
            @PathVariable Long employeeId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getCalendarByEmployee(employeeId, startDate, endDate));
    }

    @GetMapping("/team")
    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    @Operation(
            summary = "Get a manager's team leave calendar",
            description = "Returns every leave calendar entry for the direct reports of the given manager "
                    + "between startDate and endDate, inclusive."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Calendar entries returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the leave read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No manager with the given id")
    })
    public ResponseEntity<List<LeaveCalendarDto>> getTeamCalendar(
            @RequestParam Long managerId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getTeamCalendar(managerId, startDate, endDate));
    }

    @GetMapping("/organization/{organizationId}/grouped")
    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    @Operation(
            summary = "Get an organization's leave calendar grouped by date",
            description = "Returns the organization's leave calendar entries between startDate and endDate, "
                    + "inclusive, keyed by date for a day-by-day view."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Grouped calendar returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the leave read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No organization with the given id")
    })
    public ResponseEntity<Map<LocalDate, List<LeaveCalendarDto>>> getCalendarGroupedByDate(
            @PathVariable Long organizationId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getCalendarGroupedByDate(organizationId, startDate, endDate));
    }

    @GetMapping("/organization/{organizationId}/count")
    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    @Operation(
            summary = "Count employees on leave for a day",
            description = "Returns the number of employees in the organization who are on leave on the "
                    + "given date."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Count returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the leave read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No organization with the given id")
    })
    public ResponseEntity<Map<String, Long>> getEmployeesOnLeaveCount(
            @PathVariable Long organizationId,
            @RequestParam LocalDate date) {
        long count = calendarService.countEmployeesOnLeave(organizationId, date);
        return ResponseEntity.ok(Map.of("count", count));
    }
}
