package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.task.dto.TaskDto;
import org.tornotron.echno_backend.task.dto.TaskSimpleDto;

import java.util.stream.Collectors;

@Component
public class TaskDtoConvertor {

    public static TaskSimpleDto convertTaskToSimpleDto(Task task) {
        TaskSimpleDto dto = new TaskSimpleDto();
        dto.setId(task.getId());
        dto.setTitle(task.getTitle());
        dto.setStartDate(task.getStartDate());
        dto.setEndDate(task.getEndDate());
        dto.setCreatorId(task.getCreator().getId());
        dto.setProjectId(task.getProject().getId());
        dto.setCategoryId(task.getCategory().getId());
        dto.setProgress(task.getProgress());
        dto.setTags(task.getTags());
        dto.setCreatedAt(task.getCreatedAt());
        dto.setUpdatedAt(task.getUpdatedAt());
        dto.setStatus(task.getStatus());
        return dto;
    }


    public static TaskDto convertTaskToDto(Task task) {
        TaskDto dto = new TaskDto();
        dto.setId(task.getId());
        dto.setTitle(task.getTitle());
        dto.setStartDate(task.getStartDate());
        dto.setEndDate(task.getEndDate());
        dto.setCreator(EmployeeDtoConvertor.convertEmployeeToDto(task.getCreator()));
        dto.setProjectId(task.getProject().getId());
        dto.setAssignees(task.getAssignees().stream()
                .map(EmployeeDtoConvertor::convertEmployeeToDto)
                .collect(Collectors.toSet()));
        dto.setCategory(CategoryDtoConvertor.convertCategoryToDto(task.getCategory()));
        dto.setProgress(task.getProgress());
        dto.setTags(task.getTags());
        dto.setCreatedAt(task.getCreatedAt());
        dto.setUpdatedAt(task.getUpdatedAt());
        dto.setStatus(task.getStatus());
        dto.setIssues(task.getIssues().stream()
                .map(IssueDtoConvertor::convertIssueToDto)
                .collect(Collectors.toList()));
        return dto;
    }

}
