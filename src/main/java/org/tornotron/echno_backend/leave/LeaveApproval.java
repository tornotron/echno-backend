package org.tornotron.echno_backend.leave;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.leave.enums.ApprovalAction;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@Table(name = "leave_approval", indexes = {
        @Index(name = "idx_leave_approval_request", columnList = "leave_request_id"),
        @Index(name = "idx_leave_approval_approver", columnList = "approver_id"),
        @Index(name = "idx_leave_approval_action", columnList = "action")
})
public class LeaveApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leave_request_id", nullable = false)
    private LeaveRequest leaveRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approver_id", nullable = false)
    private Employee approver;

    @Column(name = "approval_level", nullable = false)
    private Integer approvalLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private ApprovalAction action;

    @Column(name = "comments", length = 1000)
    private String comments;

    @Column(name = "delegated_from_id")
    private Long delegatedFromId;

    @Column(name = "action_at")
    private LocalDateTime actionAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
