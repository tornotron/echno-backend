package org.tornotron.echno_backend.attendance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.attendance.enums.RegularizationStatus;

import java.util.List;
import java.util.Optional;

public interface AttendanceRegularizationRepository extends JpaRepository<AttendanceRegularization, Long> {

    Optional<AttendanceRegularization> findByAttendanceId(Long attendanceId);

    List<AttendanceRegularization> findByStatus(RegularizationStatus status);

    List<AttendanceRegularization> findByRequestedBy(String requestedBy);

    @Query("SELECT COUNT(r) FROM AttendanceRegularization r " +
           "WHERE r.requestedBy = :requestedBy " +
           "AND r.status != org.tornotron.echno_backend.attendance.enums.RegularizationStatus.REJECTED " +
           "AND r.requestedAt >= :startOfMonth " +
           "AND r.requestedAt < :startOfNextMonth")
    long countApprovedRegularizationsInMonth(@Param("requestedBy") String requestedBy,
                                             @Param("startOfMonth") java.time.LocalDateTime startOfMonth,
                                             @Param("startOfNextMonth") java.time.LocalDateTime startOfNextMonth);
}
