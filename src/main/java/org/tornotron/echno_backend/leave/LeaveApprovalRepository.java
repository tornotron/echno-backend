package org.tornotron.echno_backend.leave;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.leave.enums.ApprovalAction;

import java.util.List;
import java.util.Optional;

public interface LeaveApprovalRepository extends JpaRepository<LeaveApproval, Long> {

    List<LeaveApproval> findByLeaveRequestIdOrderByApprovalLevelAsc(Long leaveRequestId);

    Optional<LeaveApproval> findByLeaveRequestIdAndApprovalLevel(Long leaveRequestId, Integer approvalLevel);

    Optional<LeaveApproval> findFirstByLeaveRequestIdAndApprovalLevelAndActionOrderByCreatedAtDesc(
            Long leaveRequestId,
            Integer approvalLevel,
            ApprovalAction action);

    Optional<LeaveApproval> findByLeaveRequestIdAndApproverId(Long leaveRequestId, Long approverId);

    List<LeaveApproval> findByApproverIdAndAction(Long approverId, ApprovalAction action);

    @Query("SELECT la FROM LeaveApproval la " +
           "WHERE la.leaveRequest.id = :requestId " +
           "AND la.action = 'PENDING' " +
           "ORDER BY la.approvalLevel ASC")
    List<LeaveApproval> findPendingApprovalsByRequestId(@Param("requestId") Long requestId);

    @Query("SELECT la FROM LeaveApproval la " +
           "WHERE la.leaveRequest.id = :requestId " +
           "AND la.action != 'PENDING' " +
           "ORDER BY la.approvalLevel ASC")
    List<LeaveApproval> findCompletedApprovalsByRequestId(@Param("requestId") Long requestId);

    boolean existsByLeaveRequestIdAndApproverIdAndAction(Long leaveRequestId, Long approverId, ApprovalAction action);

    @Query("SELECT COUNT(la) FROM LeaveApproval la " +
           "WHERE la.approver.id = :approverId " +
           "AND la.action = 'PENDING'")
    long countPendingByApproverId(@Param("approverId") Long approverId);

    /**
     * Whether this approver currently holds a pending approval on one of the employee's leave
     * requests that is itself still pending. Covers the delegate, who is named on the approval row
     * and in no management line.
     */
    @Query("SELECT COUNT(la) > 0 FROM LeaveApproval la " +
           "WHERE la.leaveRequest.employee.id = :employeeId " +
           "AND la.approver.id = :approverId " +
           "AND la.action = 'PENDING' " +
           "AND la.leaveRequest.status = 'PENDING_APPROVAL'")
    boolean existsPendingApprovalForEmployeeByApprover(@Param("employeeId") Long employeeId,
                                                       @Param("approverId") Long approverId);
}
