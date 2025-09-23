package org.tornotron.echno_backend.task.dto;

import lombok.Data;
import org.tornotron.echno_backend.category.dto.CategoryDto;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.task.enums.TaskStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Data
public class TaskDto {
    private Long id;
    private String title;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private EmployeeDto creator;
    private Long projectId;
    private Set<EmployeeDto> assignees;
    private CategoryDto category;
    private Double progress;
    private List<String> tags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private TaskStatus status;
}
