package org.tornotron.echno_backend.attendance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.attendance.enums.ApprovalStatus;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Attendance rows.
 *
 * <p>{@link JpaSpecificationExecutor} is here for the approval queue added in issue #692:
 * {@code AttendanceApprovalQueueSpecifications} builds one predicate that both the listing and its
 * count are read through, so the badge and the list it labels cannot drift apart.
 */
public interface AttendanceRepository extends JpaRepository<Attendance, Long>,
        JpaSpecificationExecutor<Attendance> {

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

    /**
     * The project's day, narrowed by any combination of the optional filters.
     *
     * <p>{@code approvalStatus} and {@code requiresGeofenceApproval} are two different questions
     * and both are here because neither answers the other. Every record is created PENDING and
     * nothing moves it until somebody decides, so the approval status on its own separates the
     * decided days from the undecided ones and says nothing about which of the undecided ones
     * anybody has to look at. The flag is what marks a day held for a punch outside the geofence,
     * which is the small set. Asked together they are the site's outstanding work for that day.
     */
    @Query("""
        SELECT a FROM Attendance a
        WHERE a.projectId = :projectId
          AND a.attendanceDate = :date
          AND (:status IS NULL OR a.status = :status)
          AND (:approvalStatus IS NULL OR a.approvalStatus = :approvalStatus)
          AND (:requiresApproval IS NULL OR a.requiresGeofenceApproval = :requiresApproval)
          AND (:search IS NULL OR LOWER(a.employeeName) LIKE :search)
        """)
    Page<Attendance> findWithFilters(
            @Param("projectId") Long projectId,
            @Param("date") LocalDate date,
            @Param("status") AttendanceStatus status,
            @Param("approvalStatus") ApprovalStatus approvalStatus,
            @Param("requiresApproval") Boolean requiresApproval,
            @Param("search") String search,
            Pageable pageable);

    List<Attendance> findByEmployeeIdAndAttendanceDateBetweenAndStatus(
            Long employeeId, LocalDate from, LocalDate to, AttendanceStatus status);
}
