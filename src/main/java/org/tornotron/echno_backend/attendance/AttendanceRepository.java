package org.tornotron.echno_backend.attendance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.attendance.enums.ApprovalStatus;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    Optional<Attendance> findByEmployeeIdAndAttendanceDateAndProjectId(
            Long employeeId, LocalDate attendanceDate, Long projectId);

    Optional<Attendance> findByIdAndOrganization_Id(Long id, Long organizationId);

    List<Attendance> findByEmployeeIdAndAttendanceDateBetween(
            Long employeeId, LocalDate from, LocalDate to);

    Page<Attendance> findByProjectIdAndAttendanceDateBetween(
            Long projectId, LocalDate from, LocalDate to, Pageable pageable);

    Page<Attendance> findByProjectIdAndAttendanceDate(
            Long projectId, LocalDate date, Pageable pageable);

    Page<Attendance> findByApprovalStatus(ApprovalStatus status, Pageable pageable);

    @Query("""
        SELECT a FROM Attendance a
        WHERE a.projectId = :projectId
          AND a.attendanceDate = :date
          AND (:status IS NULL OR a.status = :status)
          AND (:search IS NULL OR LOWER(a.employeeName) LIKE LOWER(CONCAT('%', :search, '%')))
        """)
    Page<Attendance> findWithFilters(
            @Param("projectId") Long projectId,
            @Param("date") LocalDate date,
            @Param("status") AttendanceStatus status,
            @Param("search") String search,
            Pageable pageable);

    List<Attendance> findByEmployeeIdAndAttendanceDateBetweenAndStatus(
            Long employeeId, LocalDate from, LocalDate to, AttendanceStatus status);
}
