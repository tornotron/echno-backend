package org.tornotron.echno_backend.leave;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.leave.enums.LeaveStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    List<LeaveRequest> findByEmployeeId(Long employeeId);


    Optional<LeaveRequest> findByIdAndOrganization_Id(Long id, Long organizationId);

    /**
     * Loads a leave request under a pessimistic write lock so concurrent
     * approve/reject actions on the same request serialize: the second waits for
     * the first to commit, then sees the updated status and is rejected by the
     * pending-approval guard instead of double-processing (double balance
     * deduction / advancing the chain twice).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM LeaveRequest r WHERE r.id = :id AND r.organization.id = :orgId")
    Optional<LeaveRequest> lockByIdAndOrganizationId(@Param("id") Long id, @Param("orgId") Long orgId);

    Page<LeaveRequest> findByEmployeeId(Long employeeId, Pageable pageable);

    List<LeaveRequest> findByEmployeeIdAndStatus(Long employeeId, LeaveStatus status);

    List<LeaveRequest> findByOrganizationId(Long organizationId);

    List<LeaveRequest> findByCurrentApproverId(Long approverId);

    List<LeaveRequest> findByCurrentApproverIdAndStatus(Long approverId, LeaveStatus status);

    @Query("SELECT DISTINCT lr FROM LeaveRequest lr " +
           "JOIN lr.approvals la " +
           "WHERE la.approver.id = :approverId")
    List<LeaveRequest> findDistinctByApproverParticipation(@Param("approverId") Long approverId);

    @Query("SELECT lr FROM LeaveRequest lr WHERE lr.employee.id = :employeeId " +
           "AND lr.status NOT IN ('CANCELLED', 'REJECTED', 'WITHDRAWN') " +
           "AND (:excludeRequestId IS NULL OR lr.id != :excludeRequestId) " +
           "AND ((lr.startDate <= :endDate AND lr.endDate >= :startDate))")
    List<LeaveRequest> findOverlappingRequests(
            @Param("employeeId") Long employeeId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("excludeRequestId") Long excludeRequestId);

    @Query("SELECT COALESCE(SUM(lr.totalDays), 0) FROM LeaveRequest lr " +
           "WHERE lr.employee.id = :employeeId " +
           "AND lr.leavePolicy.id = :policyId " +
           "AND lr.status = 'APPROVED' " +
           "AND YEAR(lr.startDate) = :year")
    Double sumApprovedDaysByEmployeePolicyYear(
            @Param("employeeId") Long employeeId,
            @Param("policyId") Long policyId,
            @Param("year") Integer year);

    @Query("SELECT COALESCE(SUM(lr.totalDays), 0) FROM LeaveRequest lr " +
           "WHERE lr.employee.id = :employeeId " +
           "AND lr.leavePolicy.id = :policyId " +
           "AND lr.status = 'PENDING_APPROVAL' " +
           "AND YEAR(lr.startDate) = :year")
    Double sumPendingDaysByEmployeePolicyYear(
            @Param("employeeId") Long employeeId,
            @Param("policyId") Long policyId,
            @Param("year") Integer year);

    @Query("SELECT lr FROM LeaveRequest lr WHERE lr.employee.id = :employeeId " +
           "AND lr.startDate >= :startDate AND lr.endDate <= :endDate")
    List<LeaveRequest> findByEmployeeIdAndDateRange(
            @Param("employeeId") Long employeeId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT lr FROM LeaveRequest lr " +
           "WHERE lr.status = 'APPROVED' " +
           "AND lr.endDate = :date")
    List<LeaveRequest> findApprovedRequestsEndingOnDate(@Param("date") LocalDate date);

    @Query("SELECT lr FROM LeaveRequest lr " +
           "WHERE lr.employee.id = :employeeId " +
           "AND lr.status = 'APPROVED' " +
           "AND lr.endDate >= :currentDate")
    List<LeaveRequest> findOngoingApprovedLeaves(
            @Param("employeeId") Long employeeId,
            @Param("currentDate") LocalDate currentDate);

    boolean existsByEmployeeIdAndStatusAndEndDateGreaterThanEqual(
            Long employeeId, LeaveStatus status, LocalDate date);

    @Query("SELECT lr FROM LeaveRequest lr " +
           "WHERE lr.organization.id = :orgId " +
           "AND lr.status IN :statuses " +
           "AND lr.startDate >= :startDate " +
           "AND lr.startDate <= :endDate")
    Page<LeaveRequest> findByOrganizationAndStatusAndDateRange(
            @Param("orgId") Long organizationId,
            @Param("statuses") List<LeaveStatus> statuses,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            Pageable pageable);

    long countByCurrentApproverIdAndStatus(Long approverId, LeaveStatus status);
}
