package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.task.dto.TaskDto;
import org.tornotron.echno_backend.task.dto.TaskSimpleDto;

import java.time.Duration;
import java.util.stream.Collectors;

@Component
public class TaskDtoConvertor {

    public static TaskSimpleDto convertTaskToSimpleDto(Task task) {
        TaskSimpleDto dto = new TaskSimpleDto();
        dto.setId(task.getId());
        dto.setTitle(task.getTitle());
        dto.setDescription(task.getDescription());
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

    public static AttachmentDto convertAttachmentToDto(Attachment attachment, FileStorageService fileStorageService) {
        AttachmentDto dto = new AttachmentDto();
        dto.setId(attachment.getId());
        dto.setUrl(fileStorageService.generateDownloadUrl(attachment.getStorageKey(), Duration.ofHours(1)));
        dto.setEntityType(attachment.getEntityType());
        dto.setContentType(attachment.getContentType());
        dto.setFileSize(attachment.getFileSize());
        dto.setFileName(attachment.getOriginalFilename());
        dto.setCreatedAt(attachment.getCreatedAt().toString());
        dto.setUpdatedAt(attachment.getUpdatedAt().toString());
        return dto;
    }


    public static TaskDto convertTaskToDto(Task task, FileStorageService fileStorageService) {
        TaskDto dto = new TaskDto();
        dto.setId(task.getId());
        dto.setTitle(task.getTitle());
        dto.setDescription(task.getDescription());
        dto.setStartDate(task.getStartDate());
        dto.setEndDate(task.getEndDate());
        dto.setCreator(EmployeeDtoConvertor.convertEmployeeToDto(task.getCreator(),fileStorageService));
        dto.setProjectId(task.getProject().getId());
        dto.setAssignees(task.getAssignees().stream()
                .map(employee -> EmployeeDtoConvertor.convertEmployeeToDto(employee,fileStorageService))
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
        dto.setAttachments(task.getAttachments().stream()
                .map(attachment -> convertAttachmentToDto(attachment,fileStorageService))
                .collect(Collectors.toList()));
        return dto;
    }

}
