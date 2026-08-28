package org.tornotron.echno_backend.task.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Payload to create a task under a project. Sent as the JSON data part of the "
        + "multipart create request.")
@Data
public class TaskCreationDto {
    @Schema(description = "Short task title.", example = "Pour foundation slab, block A")
    @NotBlank(message = "title is required")
    @Size(min = 3, max = 255, message = "title must be between 3 and 255 characters")
    private String title;

    @Schema(description = "Planned start of the task.", example = "2026-09-05T08:00:00")
    @JsonFormat(shape = JsonFormat.Shape.STRING, lenient = OptBoolean.FALSE)
    private LocalDateTime startDate;

    @Schema(description = "Planned end of the task.", example = "2026-09-07T17:00:00")
    @JsonFormat(shape = JsonFormat.Shape.STRING, lenient = OptBoolean.FALSE)
    private LocalDateTime endDate;

    @Schema(description = "Longer description of the work to be done.",
            example = "Complete formwork, reinforcement and concrete pour for the block A raft.")
    private String description;

    @Schema(description = "Id of the employee creating the task.", example = "5")
    @NotNull(message = "creatorId is required(type: Long)")
    private Long creatorId;

    @Schema(description = "Id of the project the task belongs to.", example = "42")
    @NotNull(message = "projectId is required(type: Long)")
    private Long projectId;

    /**
     * Optional. The service has always skipped an absent or empty list, so a task nobody is
     * assigned to yet is a state it supports; requiring the field contradicted that.
     */
    @Schema(description = "Ids of the employees assigned to the task. Optional; a task may start "
            + "with nobody assigned.", example = "[7, 9]")
    private List<Long> assigneeIds;

    @Schema(description = "Id of the category the task is filed under.", example = "3")
    @NotNull(message = "categoryId is required(type: Long)")
    private Long categoryId;

    @Schema(description = "Initial completion of the task, as a percentage from 0 to 100.", example = "0")
    @NotNull(message = "progress is required(type: Double)")
    private Double progress;

    /** Optional, on the same footing as the assignees: the service skips an absent or empty list. */
    @Schema(description = "Free-text tags applied to the task. Optional.",
            example = "[\"concrete\", \"structural\"]")
    private List<String> tags;

    @Schema(description = "Initial task status.", example = "TODO")
    @NotBlank(message = "status is required")
    private String status;
}
