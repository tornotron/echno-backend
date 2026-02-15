package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.dto.ProjectDto;
import org.tornotron.echno_backend.project.dto.ProjectSimpleDto;

import java.util.stream.Collectors;

@Component
public class ProjectDtoConvertor {

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
        return simpleDto;
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
        dto.setEmployees(project.getEmployees().stream()
                .map(employee -> EmployeeDtoConvertor.convertEmployeeToDto(employee, fileStorageService))
                .collect(Collectors.toList()));
        dto.setTasks(project.getTasks().stream()
                .map(task -> TaskDtoConvertor.convertTaskToDto(task, fileStorageService))
                .collect(Collectors.toList()));
        return dto;
    }

}
