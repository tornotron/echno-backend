package org.tornotron.echno_backend.IssueComment.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class IssueCommentSimpleDto {
    private Long id;
    private String comment;
    private String author;
    private LocalDateTime createdAt;

}