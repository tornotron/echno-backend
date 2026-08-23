package org.tornotron.echno_backend.task.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.category.dto.CategoryDto;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.issue.dto.IssueDto;
import org.tornotron.echno_backend.task.enums.TaskStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Schema(description = "Condensed view of a task carrying id references instead of resolved objects. "
        + "Returned when a task is created.")
@Data
public class TaskSimpleDto {
    @Schema(description = "Unique task id.", example = "108")
    private Long id;

    @Schema(description = "Short task title.", example = "Pour foundation slab, block A")
    private String title;

    @Schema(description = "Longer description of the work to be done.",
            example = "Complete formwork, reinforcement and concrete pour for the block A raft.")
    private String description;

    @Schema(description = "Planned start of the task.", example = "2026-09-05T08:00:00")
    private LocalDateTime startDate;

    @Schema(description = "Planned end of the task.", example = "2026-09-07T17:00:00")
    private LocalDateTime endDate;

    @Schema(description = "Id of the employee who created the task.", example = "5")
    private Long creatorId;

    @Schema(description = "Id of the project the task belongs to.", example = "42")
    private Long projectId;

    @Schema(description = "Id of the category the task is filed under.", example = "3")
    private Long categoryId;

    @Schema(description = "Completion of the task, as a fraction from 0 to 1.", example = "0.0")
    private Double progress;

    @Schema(description = "Free-text tags applied to the task.", example = "[\"concrete\", \"structural\"]")
    private List<String> tags;

    @Schema(description = "Creation timestamp.", example = "2026-09-01T10:15:00")
    private LocalDateTime createdAt;

    @Schema(description = "Timestamp of the last update.", example = "2026-09-06T14:30:00")
    private LocalDateTime updatedAt;

    @Schema(description = "Current task status.", example = "TODO")
    private TaskStatus status;
}
