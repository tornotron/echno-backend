package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.dto.ProjectDto;
import org.tornotron.echno_backend.project.dto.ProjectSimpleDto;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.task.dto.TaskDto;
import org.tornotron.echno_backend.teamMember.TeamMember;
import org.tornotron.echno_backend.teamMember.dto.TeamMemberDto;

import java.util.stream.Collectors;

@Component
public class ProjectDtoConvertor {

    private static TeamMemberDto convertTeamMemberToTeamMemberDTO(TeamMember teamMember) {
        TeamMemberDto teamMemberDto = new TeamMemberDto();
        teamMemberDto.setId(teamMember.getId());
        teamMemberDto.setMemberName(teamMember.getMemberName());
        teamMemberDto.setMemberEmail(teamMember.getMemberEmail());
        teamMemberDto.setMemberPhone(teamMember.getMemberPhone());
        teamMemberDto.setMemberRole(teamMember.getMemberRole());
        teamMemberDto.setMemberImage(teamMember.getMemberImage());
        return teamMemberDto;
    }

    private static TaskDto convertTaskToTaskDto(Task task) {
        TaskDto taskDto = new TaskDto();
        taskDto.setId(task.getId());
        taskDto.setTitle(task.getTitle());
        taskDto.setStartDate(task.getStartDate());
        taskDto.setEndDate(task.getEndDate());
        taskDto.setCreator(EmployeeDtoConvertor.convertEmployeeToDto(task.getCreator()));
        taskDto.setProjectId(task.getProject().getId());
        taskDto.setAssignees(task.getAssignees().stream()
                .map(EmployeeDtoConvertor::convertEmployeeToDto)
                .collect(Collectors.toSet()));
        taskDto.setCategory(CategoryDtoConvertor.convertCategoryToDto(task.getCategory()));
        taskDto.setProgress(task.getProgress());
        taskDto.setTags(task.getTags());
        taskDto.setCreatedAt(task.getCreatedAt());
        taskDto.setUpdatedAt(task.getUpdatedAt());
        taskDto.setStatus(task.getStatus());
        return taskDto;
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
        return simpleDto;
    }

    public static ProjectDto convertProjectToDto(Project project) {
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
        dto.setTeamMembers(project.getTeamMembers().stream()
                .map(ProjectDtoConvertor::convertTeamMemberToTeamMemberDTO)
                .collect(Collectors.toList()));
        dto.setTasks(project.getTasks().stream()
                .map(ProjectDtoConvertor::convertTaskToTaskDto)
                .collect(Collectors.toList()));
        return dto;
    }

}
