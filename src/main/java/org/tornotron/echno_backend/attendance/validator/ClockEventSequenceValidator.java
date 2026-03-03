package org.tornotron.echno_backend.attendance.validator;

import jakarta.validation.ValidationException;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.AttendanceSettings;
import org.tornotron.echno_backend.attendance.ClockEvent;
import org.tornotron.echno_backend.attendance.enums.ClockEventType;

import java.util.List;
import java.util.Map;

@Component
public class ClockEventSequenceValidator {

    private static final Map<ClockEventType, Integer> EVENT_ORDER = Map.of(
            ClockEventType.MORNING_CLOCK_IN, 0,
            ClockEventType.LUNCH_BREAK_START, 1,
            ClockEventType.LUNCH_BREAK_END, 2,
            ClockEventType.EVENING_CLOCK_OUT, 3
    );

    /**
     * Validates that the incoming clock event type is valid given the current attendance record
     * and the configured check-in/check-out cycles.
     */
    public void validate(ClockEventType incoming, Attendance attendance, AttendanceSettings settings) {
        int cycles = settings.getCheckInOutCycles();

        // For cycles=1: only MORNING_CLOCK_IN and EVENING_CLOCK_OUT are valid
        if (cycles == 1) {
            if (incoming == ClockEventType.LUNCH_BREAK_START
                    || incoming == ClockEventType.LUNCH_BREAK_END) {
                throw new ValidationException(
                        "Lunch break events are not configured for this project (checkInOutCycles=1)");
            }
        }

        List<ClockEventType> recorded = attendance.getClockEvents().stream()
                .map(ClockEvent::getEventType)
                .toList();

        // Check for duplicate event type
        if (recorded.contains(incoming)) {
            throw new ValidationException(
                    "Clock event " + incoming + " has already been recorded for this attendance");
        }

        // Enforce ordering: no recorded event should have an order >= incoming
        int incomingOrder = EVENT_ORDER.get(incoming);
        boolean outOfOrder = recorded.stream()
                .anyMatch(e -> EVENT_ORDER.get(e) >= incomingOrder);

        if (outOfOrder) {
            throw new ValidationException(
                    "Clock event " + incoming + " is out of sequence");
        }
    }
}
