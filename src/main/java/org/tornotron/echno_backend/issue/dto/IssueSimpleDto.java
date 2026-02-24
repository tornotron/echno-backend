package org.tornotron.echno_backend.issue.dto;

import lombok.Data;
import org.tornotron.echno_backend.issue.enums.IssueStatus;
import org.tornotron.echno_backend.issue.enums.IssueType;

import java.time.LocalDateTime;

@Data
public class IssueSimpleDto {
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
}
