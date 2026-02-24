package org.tornotron.echno_backend.issue.dto;

import lombok.Data;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentDto;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.issue.enums.IssueStatus;
import org.tornotron.echno_backend.issue.enums.IssueType;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class IssueDto {
    private Long id;
    private String title;
    private String description;
    private IssueType type;
    private IssueStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdById;
    private String createdByName;
    private Long assignedToId;
    private String assignedToName;
    private List<IssueCommentDto> issueComments;
    private List<AttachmentDto> attachments;
    private Long taskId;
    private String taskName;
}
