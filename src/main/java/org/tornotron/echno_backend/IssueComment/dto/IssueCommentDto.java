package org.tornotron.echno_backend.IssueComment.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class IssueCommentDto {
    private Long id;
    private String comment;
    private Long authorId;
    private LocalDateTime createdAt;

    /**
     * Whether the author changed the text after posting it. False on every comment written before
     * the marker existed, which is accurate: none of them could have been edited, because there
     * was no route to edit them with.
     */
    private boolean edited;

    /** When the author last changed the text, or null if they never have. */
    private LocalDateTime editedAt;
}
