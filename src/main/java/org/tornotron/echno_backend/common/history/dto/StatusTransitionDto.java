package org.tornotron.echno_backend.common.history.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "One entry in a record's status trail: what it moved from, what it moved to, "
        + "when, by whom, and whether the status was set at creation or changed afterwards. "
        + "Entries are never edited or deleted.")
@Data
public class StatusTransitionDto {

    @Schema(description = "Database id of the entry.", example = "84")
    private Long id;

    @Schema(description = "Status held before this entry. Null when there was none: the record was "
            + "created in the status below, or this is the baseline entry written when the trail "
            + "began.",
            example = "upcoming")
    private String fromStatus;

    @Schema(description = "Status held after this entry.", example = "approved")
    private String toStatus;

    @Schema(description = "How the status came about. CREATION means the record was created holding "
            + "it, UPDATE means it was changed on an existing record, and BASELINE means it is the "
            + "status the record was observed to hold when the trail began, before which nothing "
            + "was recorded.",
            example = "UPDATE")
    private String source;

    @Schema(description = "When the status came to be held. For a BASELINE entry this is when the "
            + "trail began, not when the record was created.",
            example = "2026-08-30T11:14:02")
    private LocalDateTime occurredAt;

    @Schema(description = "Id of the user who made the change. Null where no user context was "
            + "available, which is the case for every BASELINE entry.",
            example = "17")
    private Long changedBy;

    @Schema(description = "That user's name as it read at the time, kept beside the id so a rename "
            + "or a removal does not rewrite history.",
            example = "Anand Rajashekar")
    private String changedByName;

    @Schema(description = "Anything recorded about the change beyond the two statuses. Optional.",
            example = "Approved after the site survey was signed off")
    private String note;
}
