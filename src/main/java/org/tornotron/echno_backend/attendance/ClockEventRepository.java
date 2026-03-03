package org.tornotron.echno_backend.attendance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClockEventRepository extends JpaRepository<ClockEvent, Long> {

    List<ClockEvent> findByAttendanceIdOrderByEventTimestampAsc(Long attendanceId);

    Optional<ClockEvent> findByAttendanceIdAndEventType(Long attendanceId,
                                                         org.tornotron.echno_backend.attendance.enums.ClockEventType eventType);
}
