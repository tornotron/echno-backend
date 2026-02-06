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
@RequestMapping("/api/v1/leave-calendar/web")
@Validated
public class LeaveCalendarControllerWeb {

    private final LeaveCalendarService calendarService;

    public LeaveCalendarControllerWeb(LeaveCalendarService calendarService) {
        this.calendarService = calendarService;
    }

    @GetMapping("/organization")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    public ResponseEntity<List<LeaveCalendarDto>> getOrganizationCalendar(
            @RequestParam Long organizationId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getCalendarByOrganization(organizationId, startDate, endDate));
    }

    @GetMapping("/department")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    public ResponseEntity<List<LeaveCalendarDto>> getDepartmentCalendar(
            @RequestParam Long organizationId,
            @RequestParam String department,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getCalendarByDepartment(organizationId, department, startDate, endDate));
    }

    @GetMapping("/employee")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    public ResponseEntity<List<LeaveCalendarDto>> getEmployeeCalendar(
            @RequestParam Long employeeId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getCalendarByEmployee(employeeId, startDate, endDate));
    }

    @GetMapping("/team")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    public ResponseEntity<List<LeaveCalendarDto>> getTeamCalendar(
            @RequestParam Long managerId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getTeamCalendar(managerId, startDate, endDate));
    }

    @GetMapping("/grouped")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    public ResponseEntity<Map<LocalDate, List<LeaveCalendarDto>>> getCalendarGroupedByDate(
            @RequestParam Long organizationId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(
                calendarService.getCalendarGroupedByDate(organizationId, startDate, endDate));
    }

    @GetMapping("/count")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    public ResponseEntity<Map<String, Long>> getEmployeesOnLeaveCount(
            @RequestParam Long organizationId,
            @RequestParam LocalDate date) {
        long count = calendarService.countEmployeesOnLeave(organizationId, date);
        return ResponseEntity.ok(Map.of("count", count));
    }
}
