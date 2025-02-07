package org.tornotron.echno_backend.common.validator;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.enums.RecordType;
import org.tornotron.echno_backend.common.exception.InvalidAttendanceSequenceException;

import java.util.*;

@Component
public class AttendanceSequenceValidator {

    public void validateRecordTypeSequence(Optional<Attendance> lastRecord, RecordType newRecordType) {

        if (lastRecord.isEmpty()) {
            if (newRecordType != RecordType.CHECK_IN) {
                throw new InvalidAttendanceSequenceException(
                        "First record of the day must be CHECK_IN"
                );
            }
            return;
        }

        RecordType lastRecordType = lastRecord.get().getRecordType();

        Map<RecordType, Set<RecordType>> validTransitions = getValidTransitions();

        if(!isValidTransition(lastRecordType, newRecordType,validTransitions)) {
            throw new InvalidAttendanceSequenceException(String.format("Invalid sequence: Cannot transition from %s to %s. Valid next states are: %s",
                    lastRecordType,
                    newRecordType,
                    validTransitions.get(lastRecordType)));
        }

        //validateTimeDifference



    }

    private Map<RecordType, Set<RecordType>> getValidTransitions() {

        Map<RecordType, Set<RecordType>> transitions = new HashMap<>();

        transitions.put(RecordType.CHECK_IN, new HashSet<>(Arrays.asList(
                RecordType.BREAK_START,
                RecordType.CHECK_OUT
        )));

        transitions.put(RecordType.BREAK_START,new HashSet<>(Arrays.asList(
                RecordType.BREAK_END
        )));

        transitions.put(RecordType.BREAK_END, new HashSet<>(Arrays.asList(
                RecordType.BREAK_START,
                RecordType.CHECK_OUT
        )));

        transitions.put(RecordType.CHECK_OUT, new HashSet<>(Arrays.asList(
                RecordType.CHECK_IN
        )));

        return transitions;
    }

    private boolean isValidTransition(
            RecordType currentState,
            RecordType nextState,
            Map<RecordType,Set<RecordType>> validTransitions
    ) {
        Set<RecordType> validNextState = validTransitions.get(currentState);
        return validNextState != null && validNextState.contains(nextState);
    }

}
