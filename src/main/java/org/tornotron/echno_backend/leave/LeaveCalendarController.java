package org.tornotron.echno_backend.leave;

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
public class LeaveCalendarController {

    private final LeaveCalendarService calendarService;

    public LeaveCalendarController(LeaveCalendarService calendarService) {
        this.calendarService = calendarService;
    }

    @GetMapping("/organization/{organizationId}")
    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    public ResponseEntity<List<LeaveCalendarDto>> getOrganizationCalendar(
            @PathVariable Long organizationId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getCalendarByOrganization(organizationId, startDate, endDate));
    }

    @GetMapping("/organization/{organizationId}/department")
    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
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
    public ResponseEntity<List<LeaveCalendarDto>> getEmployeeCalendar(
            @PathVariable Long employeeId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getCalendarByEmployee(employeeId, startDate, endDate));
    }

    @GetMapping("/team")
    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    public ResponseEntity<List<LeaveCalendarDto>> getTeamCalendar(
            @RequestParam Long managerId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getTeamCalendar(managerId, startDate, endDate));
    }

    @GetMapping("/organization/{organizationId}/grouped")
    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    public ResponseEntity<Map<LocalDate, List<LeaveCalendarDto>>> getCalendarGroupedByDate(
            @PathVariable Long organizationId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getCalendarGroupedByDate(organizationId, startDate, endDate));
    }

    @GetMapping("/organization/{organizationId}/count")
    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    public ResponseEntity<Map<String, Long>> getEmployeesOnLeaveCount(
            @PathVariable Long organizationId,
            @RequestParam LocalDate date) {
        long count = calendarService.countEmployeesOnLeave(organizationId, date);
        return ResponseEntity.ok(Map.of("count", count));
    }
}
