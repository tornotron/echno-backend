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
}
