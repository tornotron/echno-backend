package org.tornotron.echno_backend.task.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.task.enums.TaskStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The fields a partial task update may carry, and the type each one is read as.
 *
 * <p>The endpoint itself still takes the update as a map, because a partial update has to tell
 * "field absent" from "field explicitly set to null", and a bean cannot: both arrive as a null
 * property. Fields here are cleared by sending an explicit null, so replacing the map with a bean
 * would silently drop that. What the map costs is the published contract: a map is
 * {@code additionalProperties} in OpenAPI, so the document says nothing about which keys exist or
 * what they hold, and a caller sending a key the service does not know gets a 200 and no change.
 *
 * <p>This class is the missing half. It is referenced from the endpoint as the request schema, so
 * the published document names the fields and their types while the runtime keeps the map and its
 * null semantics. Nothing deserializes into it and nothing constructs it.
 *
 * <p>Its field list is kept honest by {@code PartialUpdateSchemaContractTest}, which reads the keys
 * {@code TaskService.partialUpdateATask} actually accepts out of that method's source and fails
 * when they and these fields have drifted apart.
 */
@Schema(description = "Fields a partial task update may change. "
        + "Every field is optional and an absent field is left untouched. A field this schema "
        + "declares nullable is cleared by sending an explicit null; a field it does not declare "
        + "nullable refuses a null with a 400 rather than clearing. Keys not listed here are "
        + "ignored.")
@Data
public class TaskUpdateFieldsDto {

    @Schema(nullable = true, description = "Short task title.", example = "Pour foundation slab, block A")
    private String title;

    @Schema(nullable = true, description = "Longer description of the work to be done.",
            example = "Complete formwork, reinforcement and concrete pour for the block A raft.")
    private String description;

    @Schema(nullable = true, description = "Planned start of the task.", example = "2026-09-05T08:00:00")
    private LocalDateTime startDate;

    @Schema(nullable = true, description = "Planned end of the task.", example = "2026-09-07T17:00:00")
    private LocalDateTime endDate;

    @Schema(nullable = true, description = "Fraction of the task completed, between 0 and 1. A value that is neither "
            + "null nor a number is ignored.", example = "0.4")
    private Double progress;

    @Schema(description = "Lifecycle status of the task. Cannot be cleared: the column is NOT NULL, "
            + "so a null is refused with a 400 rather than applied.")
    private TaskStatus status;

    @Schema(nullable = true, description = "Free-form labels on the task. Replaces the existing labels rather than "
            + "adding to them, and must be a list of strings.", example = "[\"concrete\", \"block-a\"]")
    private List<String> tags;

    @Schema(nullable = true, description = "Employees the task is assigned to. Replaces the existing assignees "
            + "rather than adding to them, so a shorter list unassigns and an empty list or a null "
            + "clears the task. Every id must belong to an employee of the caller's organization.",
            example = "[12, 31]")
    private List<Long> assigneeIds;

    @Schema(description = "Work category the task belongs to. Cannot be cleared: a task is created "
            + "with a category and a task without one is a state creation cannot produce. This one "
            + "is a product rule rather than a column rule, since the join column itself is "
            + "nullable. A null is refused with a 400.", example = "4")
    private Long categoryId;
}
