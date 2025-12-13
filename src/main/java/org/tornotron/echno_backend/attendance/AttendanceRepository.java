package org.tornotron.echno_backend.attendance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.attendance.enums.RecordType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

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
    Optional<Attendance> findLatestRecordForEmployee(@Param("employeeId") Long employeeId);

    @Query("SELECT a FROM Attendance a WHERE a.employeeId = :employeeId AND a.recordType = :recordType AND a.timestamp BETWEEN :startDate AND :endDate ORDER BY a.timestamp DESC")
    List<Attendance> findByEmployeeIdAndRecordTypeAndTimestampBetween(
            @Param("employeeId") Long employeeId,
            @Param("recordType") RecordType recordType,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    @Query("SELECT a FROM Attendance a WHERE a.modifiedBy IS NOT NULL AND a.timestamp BETWEEN :startDate AND :endDate ORDER BY a.lastModifiedAt DESC")
    List<Attendance> findCorrectedRecords(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    @Query("SELECT a FROM Attendance a WHERE a.employeeId = :employeeId AND a.modifiedBy IS NOT NULL ORDER BY a.lastModifiedAt DESC")
    List<Attendance> findCorrectedRecordsByEmployee(@Param("employeeId") Long employeeId);

    @Query(value = """
        SELECT DISTINCT a.employee_id
        FROM attendance a
        WHERE DATE(a.timestamp) = :date
        """, nativeQuery = true)
    List<Long> findEmployeesPresentOnDate(@Param("date") LocalDateTime date);
}
