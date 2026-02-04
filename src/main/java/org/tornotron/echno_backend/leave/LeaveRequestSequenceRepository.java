package org.tornotron.echno_backend.leave;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface LeaveRequestSequenceRepository extends JpaRepository<LeaveRequestSequence, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT lrs FROM LeaveRequestSequence lrs " +
           "WHERE lrs.organization.id = :orgId AND lrs.year = :year")
    Optional<LeaveRequestSequence> findByOrganizationIdAndYearWithLock(
            @Param("orgId") Long organizationId,
            @Param("year") Integer year);

    Optional<LeaveRequestSequence> findByOrganizationIdAndYear(Long organizationId, Integer year);
}
