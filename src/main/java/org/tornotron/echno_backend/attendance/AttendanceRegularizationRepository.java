package org.tornotron.echno_backend.attendance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.attendance.enums.RegularizationStatus;

import java.util.List;
import java.util.Optional;

public interface AttendanceRegularizationRepository
        extends JpaRepository<AttendanceRegularization, Long>,
        JpaSpecificationExecutor<AttendanceRegularization> {

    Optional<AttendanceRegularization> findByAttendanceId(Long attendanceId);

    Optional<AttendanceRegularization> findByIdAndOrganization_Id(Long id, Long organizationId);

    /**
     * One page of the requests in a given status.
     *
     * <p>Replaced an unpaged {@code findByStatus} that the pending register read in full. That
     * register grows with a tenant's attendance history and nothing bounded it, so the read is
     * now bounded by {@code org.tornotron.echno_backend.common.pagination.UnpagedResultCap}
     * rather than by how long the tenant has been running. The unpaged variant is gone rather
     * than left beside this one, so the next caller cannot reach for it by accident.
     *
     * @param status   The status to return.
     * @param pageable The page to read.
     * @return That page of requests, with the true total.
     */
    Page<AttendanceRegularization> findByStatus(RegularizationStatus status, Pageable pageable);

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
