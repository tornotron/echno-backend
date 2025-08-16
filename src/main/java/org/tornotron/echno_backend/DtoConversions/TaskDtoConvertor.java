package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.task.dto.TaskDto;

import java.util.stream.Collectors;

@Component
public class TaskDtoConvertor {

    public static TaskDto convertTaskToDto(Task task) {
        TaskDto dto = new TaskDto();
        dto.setId(task.getId());
        dto.setTitle(task.getTitle());
        dto.setStartDate(task.getStartDate());
        dto.setEndDate(task.getEndDate());
        dto.setCreatorId(task.getCreator().getId());
        dto.setProjectId(task.getProject().getId());
        dto.setAssigneeIds(task.getAssignees().stream()
                .map(Employee::getId)
                .collect(Collectors.toSet()));
        dto.setCategoryId(task.getCategory().getId());
        dto.setProgress(task.getProgress());
        dto.setTags(task.getTags());
        dto.setCreatedAt(task.getCreatedAt());
        dto.setUpdatedAt(task.getUpdatedAt());
        dto.setStatus(task.getStatus());
        return dto;
    }

}
