package org.tornotron.echno_backend.attendance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AttendanceRepository extends JpaRepository<Attendance,Long> {

    List<Attendance> findByEmployeeIdAndTimestampBetween(
            Long employeeId,
            LocalDateTime startDate,
            LocalDateTime endDate
    );

    @Query(value = """
        SELECT * FROM attendance
        WHERE employee_id = :employeeId
        AND DATE(timestamp) = CURRENT_DATE
        ORDER BY timestamp DESC
                LIMIT 1
        """, nativeQuery = true)
    Optional<Attendance> findLatestRecordForEmployee(Long employeeId);
}
