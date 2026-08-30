package org.tornotron.echno_backend.issue.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.tornotron.echno_backend.issue.enums.IssueStatus;

@Data
public class IssueCreationDto {

    /** The column is VARCHAR(255); the cap matches it rather than the shorter figure that never ran. */
    @NotBlank
    @Size(min = 3, max = 255, message = "Title must be between 3 and 255 characters")
    private String title;

    private Long taskId;

    /**
     * The column is TEXT. The cap sits well above what the form offers rather than at the 500
     * that was written here and never ran, and the message no longer claims a minimum of ten
     * that the constraint never asked for.
     */
    @NotBlank
    @Size(min = 5, max = 2000, message = "Description must be between 5 and 2000 characters")
    private String description;

    /**
     * The canonical name is {@code type}, which is what the column and the entity call it.
     *
     * <p>The alias is transitional. Published versions of echno-core send {@code issueType} and
     * reach the deployed web app only through a package release, so the name cannot change on
     * both sides at once: the backend accepts either until every client has moved, and the
     * update path in {@code IssueService} takes both for the same reason. Once echno-web is
     * running a core that sends {@code type}, the alias and that second case both go.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @JsonAlias("issueType")
    private String type;

    /**
     * The state the issue starts in. Optional, and {@code open} is the only value accepted:
     * raising an issue is open to every member of the tenant, while moving one is not, so any
     * other starting value would be a state the same caller could not have reached by asking
     * for it. See {@code IssueService.addIssue}.
     */
    @Schema(description = "State the issue starts in. Optional, and open is the only value "
            + "accepted, so leaving it out is the same as sending open. Every later state change "
            + "goes through PATCH /issues/{id}, which only a system-admin or project-manager may "
            + "call; being able to raise an issue that is already resolved or closed would hand "
            + "every member the one move that endpoint exists to withhold.",
            example = "open", allowableValues = {"open"})
    private IssueStatus status;

    private Long createdById;

    private Long assignedToId;
}
