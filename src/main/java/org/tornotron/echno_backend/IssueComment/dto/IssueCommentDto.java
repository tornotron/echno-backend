package org.tornotron.echno_backend.IssueComment.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class IssueCommentDto {
    private Long id;
    private String comment;
    private Long authorId;
    private LocalDateTime createdAt;
}
