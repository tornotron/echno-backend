package org.tornotron.echno_backend.leave;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, Long> {

    Optional<LeaveBalance> findByEmployeeIdAndLeavePolicyIdAndYear(Long employeeId, Long policyId, Integer year);

    /**
     * Loads a leave balance with a pessimistic write lock (SELECT ... FOR UPDATE)
     * so concurrent adjustments to the same balance serialize instead of reading
     * the same accrued/used values and overwriting each other (which erased
     * adjustments and could overdraw the balance).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT lb FROM LeaveBalance lb WHERE lb.employee.id = :employeeId "
            + "AND lb.leavePolicy.id = :policyId AND lb.year = :year")
    Optional<LeaveBalance> lockByEmployeeIdAndLeavePolicyIdAndYear(
            @Param("employeeId") Long employeeId, @Param("policyId") Long policyId,
            @Param("year") Integer year);

    List<LeaveBalance> findByEmployeeIdAndYear(Long employeeId, Integer year);

    List<LeaveBalance> findByEmployeeId(Long employeeId);

    @Query("SELECT lb FROM LeaveBalance lb " +
           "JOIN lb.leavePolicy lp " +
           "WHERE lb.employee.id = :employeeId " +
           "AND lb.year = :year " +
           "AND lp.isActive = true " +
           "ORDER BY lp.displayOrder ASC")
    List<LeaveBalance> findActiveBalancesByEmployeeAndYear(
            @Param("employeeId") Long employeeId,
            @Param("year") Integer year);

    @Query("SELECT lb FROM LeaveBalance lb " +
           "JOIN lb.employee e " +
           "WHERE e.organization.id = :orgId " +
           "AND lb.year = :year")
    List<LeaveBalance> findByOrganizationIdAndYear(
            @Param("orgId") Long organizationId,
            @Param("year") Integer year);

    boolean existsByEmployeeIdAndLeavePolicyIdAndYear(Long employeeId, Long policyId, Integer year);

    @Query("SELECT COALESCE(SUM(lb.used), 0) FROM LeaveBalance lb " +
           "WHERE lb.employee.id = :employeeId AND lb.year = :year")
    Double sumUsedByEmployeeAndYear(@Param("employeeId") Long employeeId, @Param("year") Integer year);
}
