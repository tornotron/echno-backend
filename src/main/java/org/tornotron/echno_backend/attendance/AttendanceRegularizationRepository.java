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

    /**
     * Whether an attendance record has a request in the given status.
     *
     * <p>Replaced a single-result {@code findByAttendanceId}. A record can carry more than one
     * request over its life, for instance one rejected and a second raised afterwards, and the
     * single-result lookup threw on the second request instead of answering the only question the
     * caller had, which is whether one is still pending.
     *
     * @param attendanceId The attendance record.
     * @param status       The status to look for.
     * @return Whether such a request exists.
     */
    boolean existsByAttendanceIdAndStatus(Long attendanceId, RegularizationStatus status);

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
