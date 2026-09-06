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

/**
 * Web-console equivalent of {@link LeaveCalendarController}, addressing a department or employee by
 * query parameters instead of path segments.
 *
 * <p>This twin was never phantom-guarded, so unlike the phone twin it worked. What it carried
 * instead was the shape closed in #683: it asked for the system-admin or hr-admin role and then
 * answered about whichever organization, employee or manager the caller named. An administrator
 * read any manager's team calendar by naming them, and the manager whose team it was, holding
 * neither role, was refused it.
 *
 * <p>The organization and the manager are settled by the session and are gone from the surface.
 * Dropping them is safe on the deployed console, because Spring ignores a query parameter no
 * handler declares: the calls {@code echno-core} makes today are served the caller's own tenant
 * and the caller's own team rather than refused. The organization-wide views keep the role pair,
 * so nothing here is widened except an employee's sight of their own calendar and a manager's of
 * their own team.
 */
@RestController
@RequestMapping("/api/v1/leave-calendar/web")
@Validated
@Tag(
        name = "Leave Calendar (Web)",
        description = "Web-console equivalent of the leave calendar endpoints, addressing a department or "
                + "employee by query parameters instead of path segments. Covers the organization, "
                + "department, employee, team, grouped-by-date and headcount views. The organization is "
                + "taken from the caller's session; the organization-wide views are gated to the "
                + "system-admin or hr-admin role, an employee's own calendar to that employee or those "
                + "roles, and the team view to the manager whose team it is."
)
public class LeaveCalendarControllerWeb {

    private final LeaveCalendarService calendarService;

    public LeaveCalendarControllerWeb(LeaveCalendarService calendarService) {
        this.calendarService = calendarService;
    }

    @GetMapping("/organization")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Get your organization's leave calendar",
            description = "Returns every leave calendar entry for the caller's organization between "
                    + "startDate and endDate, inclusive. The organization used to be a query parameter "
                    + "the guard never read; it comes from the session now, and a caller that still "
                    + "sends organizationId is served their own tenant."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Calendar entries returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<LeaveCalendarDto>> getOrganizationCalendar(
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getCalendarByOrganization(startDate, endDate));
    }

    @GetMapping("/department")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Get a department's leave calendar",
            description = "Returns every leave calendar entry for the named department within the "
                    + "caller's organization between startDate and endDate, inclusive."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Calendar entries returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<LeaveCalendarDto>> getDepartmentCalendar(
            @RequestParam String department,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getCalendarByDepartment(department, startDate, endDate));
    }

    @GetMapping("/employee")
    @PreAuthorize("@orgSecurity.isSelfOrHasAnyOrgRole(#employeeId, 'system-admin', 'hr-admin')")
    @Operation(
            summary = "Get an employee's leave calendar",
            description = "Returns every leave calendar entry for the employee between startDate and "
                    + "endDate, inclusive. Readable by that employee, and by the leave administrators."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Calendar entries returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither the employee named nor a holder of the system-admin or hr-admin role"),
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
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Get your team's leave calendar",
            description = "Returns every leave calendar entry for the signed-in caller's direct reports "
                    + "between startDate and endDate, inclusive. The manager used to be a query "
                    + "parameter under a guard that never read it, so an administrator read any "
                    + "manager's team and a manager could not read their own; a caller that still sends "
                    + "managerId is served their own team, because a query parameter no handler declares "
                    + "is ignored. A caller with no direct reports gets an empty list."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Calendar entries returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant, or has no employee record in it, so there is no team to serve")
    })
    public ResponseEntity<List<LeaveCalendarDto>> getTeamCalendar(
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getMyTeamCalendar(startDate, endDate));
    }

    @GetMapping("/grouped")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Get your organization's leave calendar grouped by date",
            description = "Returns the caller's organization's leave calendar entries between startDate "
                    + "and endDate, inclusive, keyed by date for a day-by-day view."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Grouped calendar returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<Map<LocalDate, List<LeaveCalendarDto>>> getCalendarGroupedByDate(
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getCalendarGroupedByDate(startDate, endDate));
    }

    @GetMapping("/count")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Count employees on leave for a day",
            description = "Returns the number of employees in the caller's organization who are on leave "
                    + "on the given date."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Count returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<Map<String, Long>> getEmployeesOnLeaveCount(
            @RequestParam LocalDate date) {
        long count = calendarService.countEmployeesOnLeave(date);
        return ResponseEntity.ok(Map.of("count", count));
    }
}
