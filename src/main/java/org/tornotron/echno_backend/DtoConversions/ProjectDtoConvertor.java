package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.dto.ProjectDto;
import org.tornotron.echno_backend.project.dto.ProjectSimpleDto;
import org.tornotron.echno_backend.task.Task;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class ProjectDtoConvertor {

    public static Double calculateProjectProgress(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return 0.0;
        }
        List<Double> progressValues = tasks.stream()
                .map(Task::getProgress)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (progressValues.isEmpty()) {
            return 0.0;
        }
        return progressValues.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
    }

    public static ProjectSimpleDto convertProjectToSimpleDto(Project project) {
        ProjectSimpleDto simpleDto = new ProjectSimpleDto();
        simpleDto.setId(project.getId());
        simpleDto.setProjectName(project.getProjectName());
        simpleDto.setProjectAddress(project.getProjectAddress());
        simpleDto.setCreatedAt(project.getCreatedAt());
        simpleDto.setStatus(project.getStatus());
        simpleDto.setProjectLatitude(project.getProjectLatitude());
        simpleDto.setProjectLongitude(project.getProjectLongitude());
        simpleDto.setStartDate(project.getStartDate());
        simpleDto.setEndDate(project.getEndDate());
        simpleDto.setProgress(project.getProgress());
        return simpleDto;
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

    public static ProjectDto convertProjectToDto(Project project, FileStorageService fileStorageService) {
        ProjectDto dto = new ProjectDto();
        dto.setId(project.getId());
        dto.setProjectName(project.getProjectName());
        dto.setProjectAddress(project.getProjectAddress());
        dto.setProjectLatitude(project.getProjectLatitude());
        dto.setProjectLongitude(project.getProjectLongitude());
        dto.setStatus(project.getStatus());
        dto.setCreatedAt(project.getCreatedAt());
        dto.setStartDate(project.getStartDate());
        dto.setEndDate(project.getEndDate());
        dto.setProgress(calculateProjectProgress(project.getTasks()));
        dto.setEmployees(project.getEmployees().stream()
                .map(employee -> EmployeeDtoConvertor.convertEmployeeToDto(employee, fileStorageService))
                .collect(Collectors.toList()));
        dto.setTasks(project.getTasks().stream()
                .map(task -> TaskDtoConvertor.convertTaskToDto(task, fileStorageService))
                .collect(Collectors.toList()));
        dto.setAttachments(project.getAttachments().stream()
                .map(attachment -> convertAttachmentToDto(attachment, fileStorageService))
                .collect(Collectors.toList()));
        return dto;
    }

}
