package org.tornotron.echno_backend.leave.dto;

import lombok.Data;
import org.tornotron.echno_backend.leave.enums.ApprovalAction;

import java.time.LocalDateTime;

@Data
public class LeaveApprovalDto {
    private Long id;
    private Long leaveRequestId;
    private Long approverId;
    private String approverName;
    private String approverDesignation;
    private Integer approvalLevel;
    private ApprovalAction action;
    private String comments;
    private Long delegatedFromId;
    private String delegatedFromName;
    private LocalDateTime actionAt;
    private LocalDateTime createdAt;
}
