package org.tornotron.echno_backend.IssueComment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;


@Data
public class IssueCommentCreationDto {

    @NotBlank(message = "comment is required")
    @Size(max = 500, message = "Comment must not exceed 500 characters")
    private String comment;

    @NotNull
    private Long issueId;

    @NotBlank(message = "author is required")
    @Size(max = 100, message = "Author name must not exceed 100 characters")
    private String author;
}
