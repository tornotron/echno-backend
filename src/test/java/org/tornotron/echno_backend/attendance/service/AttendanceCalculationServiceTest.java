package org.tornotron.echno_backend.attendance.service;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.ClockEvent;
import org.tornotron.echno_backend.attendance.ShiftTiming;
import org.tornotron.echno_backend.attendance.dto.AttendanceSummaryDto;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;
import org.tornotron.echno_backend.attendance.enums.ClockEventType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for AttendanceCalculationService, the hours-and-status engine that feeds
 * payroll. The service is pure (no repositories or Spring context), so the attendance
 * and shift graph is built entirely in memory. Two responsibilities are covered:
 * recalculate() (per-day durations, overtime, late/early flags, derived status) and
 * buildMonthlySummary() (the month roll-up and effective-day arithmetic).
 */
class AttendanceCalculationServiceTest {

    private final AttendanceCalculationService service = new AttendanceCalculationService();

    private static final LocalDate DAY = LocalDate.of(2026, 8, 3);

    /** Standard 9-to-6 shift: 15 min grace, 8h minimum, 4h half day, 9h overtime threshold. */
    private ShiftTiming standardShift() {
        return ShiftTiming.builder()
                .id(1L)
                .shiftName("General")
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .lunchBreakStart(LocalTime.of(13, 0))
                .lunchBreakEnd(LocalTime.of(14, 0))
                .gracePeriodMinutes(15)
                .minimumWorkHours(BigDecimal.valueOf(8.0))
                .halfDayWorkHours(BigDecimal.valueOf(4.0))
                .overtimeThreshold(BigDecimal.valueOf(9.0))
                .build();
    }

    private ClockEvent event(ClockEventType type, int hour, int minute) {
        return ClockEvent.builder()
                .eventType(type)
                .eventTimestamp(LocalDateTime.of(DAY, LocalTime.of(hour, minute)))
                .build();
    }

    private Attendance attendanceWith(ClockEvent... events) {
        List<ClockEvent> list = new ArrayList<>(List.of(events));
        return Attendance.builder()
                .employeeId(1L)
                .clockEvents(list)
                .build();
    }

    @Test
    void fullDayWithLunch_computesSessionsBreakAndPresentStatus() {
        Attendance a = attendanceWith(
                event(ClockEventType.MORNING_CLOCK_IN, 9, 0),
                event(ClockEventType.LUNCH_BREAK_START, 13, 0),
                event(ClockEventType.LUNCH_BREAK_END, 14, 0),
                event(ClockEventType.EVENING_CLOCK_OUT, 18, 0));

        service.recalculate(a, standardShift());

        assertThat(a.getMorningSessionMinutes()).isEqualTo(240);
        assertThat(a.getAfternoonSessionMinutes()).isEqualTo(240);
        assertThat(a.getBreakDurationMinutes()).isEqualTo(60);
        assertThat(a.getTotalWorkMinutes()).isEqualTo(480);
        assertThat(a.getOvertimeMinutes()).isZero();
        assertThat(a.getIsOvertime()).isFalse();
        assertThat(a.getIsLateArrival()).isFalse();
        assertThat(a.getIsEarlyCheckout()).isFalse();
        assertThat(a.getStatus()).isEqualTo(AttendanceStatus.PRESENT);
    }

    @Test
    void beyondThreshold_recordsOvertimeAndOvertimeStatus() {
        Attendance a = attendanceWith(
                event(ClockEventType.MORNING_CLOCK_IN, 9, 0),
                event(ClockEventType.LUNCH_BREAK_START, 13, 0),
                event(ClockEventType.LUNCH_BREAK_END, 14, 0),
                event(ClockEventType.EVENING_CLOCK_OUT, 19, 30));

        service.recalculate(a, standardShift());

        // 4h morning + 5.5h afternoon = 9.5h; 0.5h past the 9h threshold = 30 min OT.
        assertThat(a.getTotalWorkMinutes()).isEqualTo(570);
        assertThat(a.getOvertimeMinutes()).isEqualTo(30);
        assertThat(a.getIsOvertime()).isTrue();
        assertThat(a.getStatus()).isEqualTo(AttendanceStatus.OVERTIME);
    }

    @Test
    void singleCycleWithoutLunch_treatsWholeSpanAsMorning() {
        // 09:00 to 17:30 with no lunch punches: whole 8.5h span counts as the morning session.
        Attendance a = attendanceWith(
                event(ClockEventType.MORNING_CLOCK_IN, 9, 0),
                event(ClockEventType.EVENING_CLOCK_OUT, 17, 30));

        service.recalculate(a, standardShift());

        assertThat(a.getMorningSessionMinutes()).isEqualTo(510);
        assertThat(a.getAfternoonSessionMinutes()).isZero();
        assertThat(a.getBreakDurationMinutes()).isZero();
        assertThat(a.getTotalWorkMinutes()).isEqualTo(510);
        assertThat(a.getIsEarlyCheckout()).isFalse();
        assertThat(a.getStatus()).isEqualTo(AttendanceStatus.PRESENT);
    }

    @Test
    void halfDayHours_deriveHalfDayStatus() {
        Attendance a = attendanceWith(
                event(ClockEventType.MORNING_CLOCK_IN, 9, 0),
                event(ClockEventType.LUNCH_BREAK_START, 13, 0));

        service.recalculate(a, standardShift());

        assertThat(a.getTotalWorkMinutes()).isEqualTo(240);
        assertThat(a.getStatus()).isEqualTo(AttendanceStatus.HALF_DAY);
    }

    @Test
    void arrivalAfterGrace_flagsLateAndDerivesLateStatus() {
        // 09:30 arrival is past the 09:15 grace end; full 8h still worked.
        Attendance a = attendanceWith(
                event(ClockEventType.MORNING_CLOCK_IN, 9, 30),
                event(ClockEventType.LUNCH_BREAK_START, 13, 0),
                event(ClockEventType.LUNCH_BREAK_END, 14, 0),
                event(ClockEventType.EVENING_CLOCK_OUT, 18, 30));

        service.recalculate(a, standardShift());

        assertThat(a.getTotalWorkMinutes()).isEqualTo(480);
        assertThat(a.getIsLateArrival()).isTrue();
        assertThat(a.getStatus()).isEqualTo(AttendanceStatus.LATE);
    }

    @Test
    void departureBeforeThreshold_flagsEarlyCheckoutStatus() {
        // 08:00 in (not late), 17:00 out is before the 17:30 early threshold; 8h worked.
        Attendance a = attendanceWith(
                event(ClockEventType.MORNING_CLOCK_IN, 8, 0),
                event(ClockEventType.LUNCH_BREAK_START, 13, 0),
                event(ClockEventType.LUNCH_BREAK_END, 14, 0),
                event(ClockEventType.EVENING_CLOCK_OUT, 17, 0));

        service.recalculate(a, standardShift());

        assertThat(a.getTotalWorkMinutes()).isEqualTo(480);
        assertThat(a.getIsLateArrival()).isFalse();
        assertThat(a.getIsEarlyCheckout()).isTrue();
        assertThat(a.getStatus()).isEqualTo(AttendanceStatus.EARLY_CHECKOUT);
    }

    @Test
    void noClockIn_isAbsent() {
        Attendance a = attendanceWith();

        service.recalculate(a, standardShift());

        assertThat(a.getTotalWorkMinutes()).isZero();
        assertThat(a.getStatus()).isEqualTo(AttendanceStatus.ABSENT);
    }

    @Test
    void leaveIdSet_shortCircuitsToLeaveStatus() {
        Attendance a = attendanceWith(
                event(ClockEventType.MORNING_CLOCK_IN, 9, 0),
                event(ClockEventType.EVENING_CLOCK_OUT, 18, 0));
        a.setLeaveId(42L);

        service.recalculate(a, standardShift());

        assertThat(a.getStatus()).isEqualTo(AttendanceStatus.LEAVE);
    }

    @Test
    void clockInOnlyWithoutClockOut_isPendingRegularization() {
        Attendance a = attendanceWith(event(ClockEventType.MORNING_CLOCK_IN, 9, 0));

        service.recalculate(a, standardShift());

        assertThat(a.getTotalWorkMinutes()).isZero();
        assertThat(a.getStatus()).isEqualTo(AttendanceStatus.PENDING_REGULARIZATION);
    }

    @Test
    void clockedOutButBelowHalfDay_isAbsent() {
        // 09:00 in, 09:30 out: 30 min single cycle, under the half-day floor, and clocked out.
        Attendance a = attendanceWith(
                event(ClockEventType.MORNING_CLOCK_IN, 9, 0),
                event(ClockEventType.EVENING_CLOCK_OUT, 9, 30));

        service.recalculate(a, standardShift());

        assertThat(a.getTotalWorkMinutes()).isEqualTo(30);
        assertThat(a.getStatus()).isEqualTo(AttendanceStatus.ABSENT);
    }

    // ==================== buildMonthlySummary ====================

    private Attendance record(AttendanceStatus status, int workMinutes, int overtimeMinutes) {
        return Attendance.builder()
                .status(status)
                .totalWorkMinutes(workMinutes)
                .overtimeMinutes(overtimeMinutes)
                .build();
    }

    @Test
    void monthlySummary_aggregatesCountsHoursAndEffectiveDays() {
        List<Attendance> records = List.of(
                record(AttendanceStatus.PRESENT, 480, 0),
                record(AttendanceStatus.PRESENT, 480, 0),
                record(AttendanceStatus.HALF_DAY, 240, 0),
                record(AttendanceStatus.ABSENT, 0, 0),
                record(AttendanceStatus.LEAVE, 0, 0),
                record(AttendanceStatus.LATE, 480, 0),
                record(AttendanceStatus.OVERTIME, 540, 60));

        AttendanceSummaryDto summary = service.buildMonthlySummary(7L, "Ravi", records, 8, 2026);

        assertThat(summary.getEmployeeId()).isEqualTo(7L);
        assertThat(summary.getPresentDays()).isEqualTo(2);
        assertThat(summary.getHalfDays()).isEqualTo(1);
        assertThat(summary.getAbsentDays()).isEqualTo(1);
        assertThat(summary.getLeaveDays()).isEqualTo(1);
        assertThat(summary.getLateDays()).isEqualTo(1);
        assertThat(summary.getOvertimeDays()).isEqualTo(1);
        // workingDays = present + half + absent + leave + late + overtime = 7 (weekly-off/holiday excluded)
        assertThat(summary.getTotalWorkingDays()).isEqualTo(7);
        // 480+480+240+0+0+480+540 = 2220 min = 37.0h
        assertThat(summary.getTotalHoursWorked()).isEqualTo(37.0);
        assertThat(summary.getTotalOvertimeHours()).isEqualTo(1.0);
        // present 2 + half 0.5 + leave 1 + late 0.9 + overtime 1 = 5.4
        assertThat(summary.getEffectiveWorkDays()).isEqualTo(5.4);
        assertThat(summary.getAverageWorkHours()).isEqualTo(37.0 / 7);
        assertThat(summary.getAttendancePercentage()).isEqualTo(5.4 / 7 * 100);
    }

    @Test
    void monthlySummary_emptyRecords_yieldsZeroesNoDivideByZero() {
        AttendanceSummaryDto summary = service.buildMonthlySummary(7L, "Ravi", List.of(), 8, 2026);

        assertThat(summary.getTotalWorkingDays()).isZero();
        assertThat(summary.getTotalHoursWorked()).isEqualTo(0.0);
        assertThat(summary.getAverageWorkHours()).isEqualTo(0.0);
        assertThat(summary.getAttendancePercentage()).isEqualTo(0.0);
    }
}
