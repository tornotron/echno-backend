package org.tornotron.echno_backend.attendance.service;

import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.ClockEvent;
import org.tornotron.echno_backend.attendance.ShiftTiming;
import org.tornotron.echno_backend.attendance.dto.AttendanceSummaryDto;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;
import org.tornotron.echno_backend.attendance.enums.ClockEventType;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AttendanceCalculationService {

    /**
     * Recalculates work duration, break duration, overtime, and status based on clock events.
     */
    public void recalculate(Attendance attendance, ShiftTiming shift) {
        Map<ClockEventType, ClockEvent> eventMap = attendance.getClockEvents().stream()
                .collect(Collectors.toMap(ClockEvent::getEventType, e -> e, (a, b) -> a));

        ClockEvent clockIn = eventMap.get(ClockEventType.MORNING_CLOCK_IN);
        ClockEvent lunchOut = eventMap.get(ClockEventType.LUNCH_BREAK_START);
        ClockEvent lunchIn = eventMap.get(ClockEventType.LUNCH_BREAK_END);
        ClockEvent clockOut = eventMap.get(ClockEventType.EVENING_CLOCK_OUT);

        int morningMinutes = 0, afternoonMinutes = 0, breakMinutes = 0;

        if (clockIn != null && lunchOut != null) {
            morningMinutes = (int) Duration.between(
                    clockIn.getEventTimestamp(), lunchOut.getEventTimestamp()).toMinutes();
        }
        if (lunchOut != null && lunchIn != null) {
            breakMinutes = (int) Duration.between(
                    lunchOut.getEventTimestamp(), lunchIn.getEventTimestamp()).toMinutes();
        }
        if (lunchIn != null && clockOut != null) {
            afternoonMinutes = (int) Duration.between(
                    lunchIn.getEventTimestamp(), clockOut.getEventTimestamp()).toMinutes();
        } else if (clockIn != null && clockOut != null && lunchOut == null) {
            // Single-cycle: no lunch break
            morningMinutes = (int) Duration.between(
                    clockIn.getEventTimestamp(), clockOut.getEventTimestamp()).toMinutes();
        }

        int totalMinutes = morningMinutes + afternoonMinutes;
        double totalHours = totalMinutes / 60.0;

        int overtimeMinutes = 0;
        double otThreshold = shift.getOvertimeThreshold().doubleValue();
        if (totalHours > otThreshold) {
            overtimeMinutes = (int) ((totalHours - otThreshold) * 60);
        }

        attendance.setMorningSessionMinutes(morningMinutes);
        attendance.setAfternoonSessionMinutes(afternoonMinutes);
        attendance.setBreakDurationMinutes(breakMinutes);
        attendance.setTotalWorkMinutes(totalMinutes);
        attendance.setOvertimeMinutes(overtimeMinutes);
        attendance.setIsOvertime(overtimeMinutes > 0);

        // Late arrival
        if (clockIn != null) {
            LocalTime arrivalTime = clockIn.getEventTimestamp().toLocalTime();
            LocalTime graceEnd = shift.getStartTime().plusMinutes(shift.getGracePeriodMinutes());
            attendance.setIsLateArrival(arrivalTime.isAfter(graceEnd));
        }

        // Early checkout
        if (clockOut != null) {
            LocalTime departureTime = clockOut.getEventTimestamp().toLocalTime();
            LocalTime earlyThreshold = shift.getEndTime().minusMinutes(30);
            attendance.setIsEarlyCheckout(departureTime.isBefore(earlyThreshold));
        }

        // Determine status
        attendance.setStatus(deriveStatus(attendance, shift, totalHours));
    }

    private AttendanceStatus deriveStatus(Attendance attendance, ShiftTiming shift, double totalHours) {
        if (attendance.getLeaveId() != null) return AttendanceStatus.LEAVE;

        boolean hasClockIn = attendance.getClockEvents().stream()
                .anyMatch(e -> e.getEventType() == ClockEventType.MORNING_CLOCK_IN);

        if (!hasClockIn) return AttendanceStatus.ABSENT;

        if (totalHours >= shift.getOvertimeThreshold().doubleValue())
            return AttendanceStatus.OVERTIME;

        if (totalHours >= shift.getMinimumWorkHours().doubleValue()) {
            if (attendance.getIsLateArrival()) return AttendanceStatus.LATE;
            if (attendance.getIsEarlyCheckout()) return AttendanceStatus.EARLY_CHECKOUT;
            return AttendanceStatus.PRESENT;
        }

        if (totalHours >= shift.getHalfDayWorkHours().doubleValue())
            return AttendanceStatus.HALF_DAY;

        boolean hasClockOut = attendance.getClockEvents().stream()
                .anyMatch(e -> e.getEventType() == ClockEventType.EVENING_CLOCK_OUT);

        return hasClockOut ? AttendanceStatus.ABSENT : AttendanceStatus.PENDING_REGULARIZATION;
    }

    /**
     * Build a monthly summary from a list of attendance records.
     */
    public AttendanceSummaryDto buildMonthlySummary(Long employeeId, String employeeName,
                                                     List<Attendance> records,
                                                     int month, int year) {
        int presentDays = 0, halfDays = 0, absentDays = 0, leaveDays = 0;
        int weeklyOffs = 0, holidays = 0, lateDays = 0, overtimeDays = 0;
        int totalWorkMinutes = 0, totalOvertimeMinutes = 0;

        for (Attendance a : records) {
            switch (a.getStatus()) {
                case PRESENT -> presentDays++;
                case HALF_DAY -> halfDays++;
                case ABSENT -> absentDays++;
                case LEAVE -> leaveDays++;
                case WEEKLY_OFF -> weeklyOffs++;
                case HOLIDAY -> holidays++;
                case LATE -> lateDays++;
                case OVERTIME -> overtimeDays++;
                default -> {}
            }
            totalWorkMinutes += a.getTotalWorkMinutes() != null ? a.getTotalWorkMinutes() : 0;
            totalOvertimeMinutes += a.getOvertimeMinutes() != null ? a.getOvertimeMinutes() : 0;
        }

        int workingDays = presentDays + halfDays + absentDays + leaveDays + lateDays + overtimeDays;
        double totalHours = totalWorkMinutes / 60.0;
        double avgHours = workingDays > 0 ? totalHours / workingDays : 0;

        double effectiveWorkDays = presentDays + (halfDays * 0.5) + leaveDays
                + weeklyOffs + holidays + (lateDays * 0.9) + overtimeDays;

        return AttendanceSummaryDto.builder()
                .employeeId(employeeId)
                .employeeName(employeeName)
                .month(month)
                .year(year)
                .totalWorkingDays(workingDays)
                .presentDays(presentDays)
                .halfDays(halfDays)
                .absentDays(absentDays)
                .leaveDays(leaveDays)
                .weeklyOffs(weeklyOffs)
                .holidays(holidays)
                .lateDays(lateDays)
                .overtimeDays(overtimeDays)
                .totalHoursWorked(totalHours)
                .totalOvertimeHours(totalOvertimeMinutes / 60.0)
                .averageWorkHours(avgHours)
                .effectiveWorkDays(effectiveWorkDays)
                .attendancePercentage(workingDays > 0
                        ? (effectiveWorkDays / workingDays) * 100 : 0)
                .build();
    }
}
