package org.tornotron.echno_backend.IssueComment.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class IssueCommentSimpleDto {
    private Long id;
    private String comment;
    private Long authorId;
    private LocalDateTime createdAt;

    /** Whether the author changed the text after posting it. */
    private boolean edited;

    /** When the author last changed the text, or null if they never have. */
    private LocalDateTime editedAt;
}