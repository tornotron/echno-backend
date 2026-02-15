package org.tornotron.echno_backend.project.dto;

import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;
import org.tornotron.echno_backend.task.dto.TaskDto;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ProjectDto {
    private Long id;
    private String projectName;
    private String projectAddress;
    private LocalDateTime createdAt;
    private ProjectCreationStatus status;
    private List<EmployeeDto> employees;
    private Float projectLatitude;
    private Float projectLongitude;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private List<TaskDto> tasks;
}
