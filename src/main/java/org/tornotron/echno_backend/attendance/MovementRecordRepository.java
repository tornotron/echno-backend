package org.tornotron.echno_backend.attendance;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MovementRecordRepository extends JpaRepository<MovementRecord, Long> {

    Optional<MovementRecord> findByIdAndOrganization_Id(Long id, Long organizationId);

    /**
     * Reads one movement record for verification, holding the row until the transaction ends.
     *
     * <p>Verification reads whether the record is already verified and then stamps it. Two
     * verifications running at once on the same row would each read it as unverified, both pass
     * the guard, and both stamp, so the second would replace a verification the first had already
     * recorded, which is the one thing the guard exists to prevent. With the lock the second
     * caller waits, reads the stamp the first committed, and is refused.
     *
     * <p>The same lock the stock-adjustment, leave-approval and payable paths take for the same
     * reason. Reads that only display a movement keep the unlocked lookup above.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM MovementRecord m WHERE m.id = :id AND m.organization.id = :orgId")
    Optional<MovementRecord> lockByIdAndOrganizationId(@Param("id") Long id, @Param("orgId") Long orgId);

    /**
     * The movements logged against one attendance record, earliest first.
     *
     * <p>The organization is a predicate here rather than left to the Hibernate {@code orgFilter}.
     * The filter does cover {@link MovementRecord}, so this is defence in depth rather than a
     * repair: it was the one read in this class that said nothing about the tenant it meant, and
     * a query that only works because a session filter happens to be enabled is the shape the
     * 2026-08-18 audit flagged as a trap for whoever calls it next.
     */
    @Query("SELECT m FROM MovementRecord m WHERE m.attendance.id = :attendanceId "
            + "AND m.organization.id = :orgId ORDER BY m.startTime ASC")
    List<MovementRecord> findByAttendanceIdAndOrganizationId(@Param("attendanceId") Long attendanceId,
                                                             @Param("orgId") Long orgId);

    List<MovementRecord> findByEmployeeIdAndStartTimeBetween(Long employeeId,
                                                              LocalDateTime from,
                                                              LocalDateTime to);
}
