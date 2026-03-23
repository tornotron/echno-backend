package org.tornotron.echno_backend.attendance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MovementRecordRepository extends JpaRepository<MovementRecord, Long> {

    Optional<MovementRecord> findByIdAndOrganization_Id(Long id, Long organizationId);

    List<MovementRecord> findByAttendanceIdOrderByStartTimeAsc(Long attendanceId);

    List<MovementRecord> findByEmployeeIdAndStartTimeBetween(Long employeeId,
                                                              LocalDateTime from,
                                                              LocalDateTime to);
}
