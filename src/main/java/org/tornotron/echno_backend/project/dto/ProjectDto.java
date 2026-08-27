package org.tornotron.echno_backend.project.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;
import org.tornotron.echno_backend.project.enums.ProjectType;
import org.tornotron.echno_backend.task.dto.TaskDto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "Full view of a construction project, including its team, tasks, attachments "
        + "and overall progress.")
@Data
public class ProjectDto {
    @Schema(description = "Unique project id.", example = "42")
    private Long id;

    @Schema(description = "Project name.", example = "Tower B, Riverside Residences")
    private String projectName;

    @Schema(description = "Site address of the project.", example = "12 Marina Road, Chennai")
    private String projectAddress;

    @Schema(description = "Creation timestamp.", example = "2026-08-01T09:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "Current project status.", example = "IN_PROGRESS")
    private ProjectCreationStatus status;

    @Schema(description = "Construction category of the project.", example = "RESIDENTIAL")
    private ProjectType projectType;

    @Schema(description = "Finance customer the project is billed to. Null when no client is set.",
            example = "6b1e9c22-9f8a-4a1b-9c0e-1d2f3a4b5c6d")
    private UUID customerId;

    @Schema(description = "Employees assigned to the project team.")
    private List<EmployeeDto> employees;

    @Schema(description = "Site latitude in decimal degrees.", example = "13.0827")
    private Float projectLatitude;

    @Schema(description = "Site longitude in decimal degrees.", example = "80.2707")
    private Float projectLongitude;

    @Schema(description = "Planned start date of the project.", example = "2026-09-01T00:00:00")
    private LocalDateTime startDate;

    @Schema(description = "Planned completion date of the project.", example = "2027-06-30T00:00:00")
    private LocalDateTime endDate;

    @Schema(description = "Overall completion of the project, as a fraction from 0 to 1.", example = "0.35")
    private Double progress;

    @Schema(description = "Tasks that belong to the project.")
    private List<TaskDto> tasks;

    @Schema(description = "Files attached to the project.")
    private List<AttachmentDto> attachments;
}
