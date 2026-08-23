package org.tornotron.echno_backend.task.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.category.dto.CategoryDto;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.issue.dto.IssueDto;
import org.tornotron.echno_backend.task.enums.TaskStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Schema(description = "Full view of a task, with its resolved creator, assignees, category, issues "
        + "and attachments.")
@Data
public class TaskDto {
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

    @Schema(description = "Employee who created the task.")
    private EmployeeDto creator;

    @Schema(description = "Id of the project the task belongs to.", example = "42")
    private Long projectId;

    @Schema(description = "Employees assigned to the task.")
    private Set<EmployeeDto> assignees;

    @Schema(description = "Category the task is filed under.")
    private CategoryDto category;

    @Schema(description = "Completion of the task, as a fraction from 0 to 1.", example = "0.5")
    private Double progress;

    @Schema(description = "Free-text tags applied to the task.", example = "[\"concrete\", \"structural\"]")
    private List<String> tags;

    @Schema(description = "Creation timestamp.", example = "2026-09-01T10:15:00")
    private LocalDateTime createdAt;

    @Schema(description = "Timestamp of the last update.", example = "2026-09-06T14:30:00")
    private LocalDateTime updatedAt;

    @Schema(description = "Current task status.", example = "IN_PROGRESS")
    private TaskStatus status;

    @Schema(description = "Issues raised against the task.")
    private List<IssueDto> issues;

    @Schema(description = "Files attached to the task.")
    private List<AttachmentDto> attachments;
}
