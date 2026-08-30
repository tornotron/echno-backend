package org.tornotron.echno_backend.IssueComment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Payload to leave a comment on an issue.
 *
 * <p>There is no author field. The author is the signed-in caller, resolved from the session in
 * {@code IssueCommentService.addIssueComment}. It used to be an {@code authorId} the caller sent,
 * checked only for being an employee of the tenant, so any member could post a comment in a
 * colleague's name; a comment is read as its author's own statement, and nobody legitimately
 * writes one as somebody else. Clients that still send {@code authorId} keep working, because a
 * property no payload declares is ignored rather than refused.
 */
@Schema(description = "Payload to leave a comment on an issue. The author is taken from the "
        + "signed-in session, not from the request.")
@Data
public class IssueCommentCreationDto {

    @Schema(description = "The comment text.", example = "Rebar spacing on the east face still needs checking.")
    @NotBlank(message = "comment is required")
    @Size(max = 500, message = "Comment must not exceed 500 characters")
    private String comment;

    @Schema(description = "Id of the issue being commented on.", example = "108")
    @NotNull
    private Long issueId;
}
