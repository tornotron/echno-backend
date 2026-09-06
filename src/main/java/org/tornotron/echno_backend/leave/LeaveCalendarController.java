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
 * Day-by-day view of who is on leave, built from approved leave requests.
 *
 * <p>All six endpoints asked for {@code hasAuthority('leave:read')} or
 * {@code hasAuthority('leave:admin')}, authorities this realm has no mechanism to issue: a bare
 * {@code resource:scope} authority is minted only by {@code JwtAuthConverter.extractPermissions}
 * from the {@code authorization} claim of an RPT, and the only automated provisioner,
 * {@code KeycloakInitializer.ensureAuthorizationSetup}, creates a scopeless {@code Default
 * Resource} and a scopeless {@code Default Permission}. A permission with no scopes yields no
 * {@code resource:scope} authority, so the whole calendar refused every caller on the phone while
 * the web twin of the same six views was live and working on the role pair.
 *
 * <p>The guards now say what the web twin already said, so nothing is widened by repairing them:
 * the organization-wide views stay with the system-admin and hr-admin roles, one employee's
 * calendar is readable by that employee or those roles, and a manager's team calendar is readable
 * by the manager whose team it is.
 *
 * <p>The paths of the four organization-wide views lost their {@code {organizationId}} segment.
 * The tenant is settled by the session and taking it from the caller was the same shape as #683,
 * with only the Hibernate {@code orgFilter} standing between a named id and another tenant's rows.
 * Reshaping the path is safe here in a way it would not normally be, because these four refused
 * every caller and so no client can be depending on the shape they had.
 */
@RestController
@RequestMapping("/api/v1/leave-calendar")
@Validated
@Tag(
        name = "Leave Calendar",
        description = "Day-by-day view of who is on leave, built from approved leave requests. Endpoints "
                + "cover the caller's whole organization, a single department, a single employee or the "
                + "caller's own team, over a given date range, plus a grouped-by-date view and a "
                + "headcount for a single day. The organization is taken from the caller's session; the "
                + "organization-wide views are gated to the system-admin or hr-admin role, an employee's "
                + "own calendar to that employee or those roles, and the team view to the manager whose "
                + "team it is."
)
public class LeaveCalendarController {

    private final LeaveCalendarService calendarService;

    public LeaveCalendarController(LeaveCalendarService calendarService) {
        this.calendarService = calendarService;
    }

    @GetMapping("/organization")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Get your organization's leave calendar",
            description = "Returns every leave calendar entry for the caller's organization between "
                    + "startDate and endDate, inclusive. The organization used to be a path segment the "
                    + "guard never read; it comes from the session now."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Calendar entries returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the system-admin or hr-admin role in the current tenant")
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the system-admin or hr-admin role in the current tenant")
    })
    public ResponseEntity<List<LeaveCalendarDto>> getDepartmentCalendar(
            @RequestParam String department,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getCalendarByDepartment(department, startDate, endDate));
    }

    @GetMapping("/employee/{employeeId}")
    @PreAuthorize("@orgSecurity.isSelfOrHasAnyOrgRole(#employeeId, 'system-admin', 'hr-admin')")
    @Operation(
            summary = "Get an employee's leave calendar",
            description = "Returns every leave calendar entry for the employee between startDate and "
                    + "endDate, inclusive. Readable by that employee, and by the leave administrators."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Calendar entries returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither the employee named nor a holder of the system-admin or hr-admin role")
    })
    public ResponseEntity<List<LeaveCalendarDto>> getEmployeeCalendar(
            @PathVariable Long employeeId,
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the system-admin or hr-admin role in the current tenant")
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the system-admin or hr-admin role in the current tenant")
    })
    public ResponseEntity<Map<String, Long>> getEmployeesOnLeaveCount(
            @RequestParam LocalDate date) {
        long count = calendarService.countEmployeesOnLeave(date);
        return ResponseEntity.ok(Map.of("count", count));
    }
}
