package org.tornotron.echno_backend.attendance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface MovementRecordRepository extends JpaRepository<MovementRecord, Long> {

    List<MovementRecord> findByAttendanceIdOrderByStartTimeAsc(Long attendanceId);

    List<MovementRecord> findByEmployeeIdAndStartTimeBetween(Long employeeId,
                                                              LocalDateTime from,
                                                              LocalDateTime to);
}
