package org.tornotron.echno_backend.IssueComment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Payload to change the text of a comment already posted.
 *
 * <p>The text is the only thing an edit may change. The issue a comment belongs to is not
 * editable, because moving a comment between issues would rewrite two threads rather than correct
 * one sentence; the author is not editable for the reason
 * {@link IssueCommentCreationDto} records, that a comment is read as its author's own statement.
 * Both are therefore absent rather than ignored, so a client sending either is told nothing was
 * done with it by the field simply not existing.
 *
 * <p>Same {@code @NotBlank} and 500-character bound as the create payload. An edit that empties a
 * comment is a delete wearing an edit's clothes, and the thread should show a deletion if that is
 * what happened.
 */
@Schema(description = "Payload to change the text of a comment. Only the comment's own author may "
        + "send it, and the comment is marked edited once they do.")
@Data
public class IssueCommentUpdateDto {

    @Schema(description = "The corrected comment text.",
            example = "Rebar spacing on the east face still needs checking, west face is signed off.")
    @NotBlank(message = "comment is required")
    @Size(max = 500, message = "Comment must not exceed 500 characters")
    private String comment;
}
