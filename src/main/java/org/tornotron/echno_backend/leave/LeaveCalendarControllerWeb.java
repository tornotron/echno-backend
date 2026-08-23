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
@RequestMapping("/api/v1/leave-calendar/web")
@Validated
@Tag(
        name = "Leave Calendar (Web)",
        description = "Web-console equivalent of the leave calendar endpoints, addressing the organization, "
                + "department, employee or manager by query parameters instead of path segments. Covers "
                + "the organization, department, employee, team, grouped-by-date and headcount views. All "
                + "endpoints are gated to the system-admin or hr-admin role in the caller's tenant."
)
public class LeaveCalendarControllerWeb {

    private final LeaveCalendarService calendarService;

    public LeaveCalendarControllerWeb(LeaveCalendarService calendarService) {
        this.calendarService = calendarService;
    }

    @GetMapping("/organization")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Get an organization's leave calendar",
            description = "Returns every leave calendar entry for the organization between startDate and "
                    + "endDate, inclusive."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Calendar entries returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No organization with the given id")
    })
    public ResponseEntity<List<LeaveCalendarDto>> getOrganizationCalendar(
            @RequestParam Long organizationId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getCalendarByOrganization(organizationId, startDate, endDate));
    }

    @GetMapping("/department")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Get a department's leave calendar",
            description = "Returns every leave calendar entry for the named department within the "
                    + "organization between startDate and endDate, inclusive."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Calendar entries returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No organization with the given id")
    })
    public ResponseEntity<List<LeaveCalendarDto>> getDepartmentCalendar(
            @RequestParam Long organizationId,
            @RequestParam String department,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getCalendarByDepartment(organizationId, department, startDate, endDate));
    }

    @GetMapping("/employee")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Get an employee's leave calendar",
            description = "Returns every leave calendar entry for the employee between startDate and "
                    + "endDate, inclusive."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Calendar entries returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<List<LeaveCalendarDto>> getEmployeeCalendar(
            @RequestParam Long employeeId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getCalendarByEmployee(employeeId, startDate, endDate));
    }

    @GetMapping("/team")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Get a manager's team leave calendar",
            description = "Returns every leave calendar entry for the direct reports of the given manager "
                    + "between startDate and endDate, inclusive."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Calendar entries returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No manager with the given id")
    })
    public ResponseEntity<List<LeaveCalendarDto>> getTeamCalendar(
            @RequestParam Long managerId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getTeamCalendar(managerId, startDate, endDate));
    }

    @GetMapping("/grouped")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Get an organization's leave calendar grouped by date",
            description = "Returns the organization's leave calendar entries between startDate and endDate, "
                    + "inclusive, keyed by date for a day-by-day view."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Grouped calendar returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No organization with the given id")
    })
    public ResponseEntity<Map<LocalDate, List<LeaveCalendarDto>>> getCalendarGroupedByDate(
            @RequestParam Long organizationId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getCalendarGroupedByDate(organizationId, startDate, endDate));
    }

    @GetMapping("/count")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Count employees on leave for a day",
            description = "Returns the number of employees in the organization who are on leave on the "
                    + "given date."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Count returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No organization with the given id")
    })
    public ResponseEntity<Map<String, Long>> getEmployeesOnLeaveCount(
            @RequestParam Long organizationId,
            @RequestParam LocalDate date) {
        long count = calendarService.countEmployeesOnLeave(organizationId, date);
        return ResponseEntity.ok(Map.of("count", count));
    }
}
