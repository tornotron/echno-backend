package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.organization.dto.OrganizationSimpleDto;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.dto.ProjectDto;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.task.dto.TaskDto;
import org.tornotron.echno_backend.teamMember.TeamMember;
import org.tornotron.echno_backend.teamMember.dto.TeamMemberDto;

import java.util.stream.Collectors;

@Component
public class OrganizationDtoConvertor {

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
        taskDto.setTags(task.getTags());
        taskDto.setCreatedAt(task.getCreatedAt());
        taskDto.setUpdatedAt(task.getUpdatedAt());
        taskDto.setStatus(task.getStatus());
        return taskDto;
    }

    private static ProjectDto convertProjectToProjectDto(Project project) {
        ProjectDto projectDto = new ProjectDto();
        projectDto.setId(project.getId());
        projectDto.setProjectName(project.getProjectName());
        projectDto.setProjectAddress(project.getProjectAddress());
        projectDto.setStatus(project.getStatus());
        projectDto.setProjectLongitude(project.getProjectLongitude());
        projectDto.setProjectLatitude(project.getProjectLatitude());
        projectDto.setStartDate(project.getStartDate());
        projectDto.setEndDate(project.getEndDate());
        projectDto.setCreatedAt(project.getCreatedAt());
        projectDto.setTeamMembers(project.getTeamMembers().stream()
                .map(OrganizationDtoConvertor::convertTeamMemberToTeamMemberDTO)
                .collect(Collectors.toList()));
        projectDto.setTasks(project.getTasks().stream()
                .map(OrganizationDtoConvertor::convertTaskToTaskDto)
                .collect(Collectors.toList()));
        return projectDto;
    }

    private static EmployeeDto convertEmployeeToEmployeeDto(Employee employee) {
        EmployeeDto employeeDto = new EmployeeDto();
        employeeDto.setId(employee.getId());
        employeeDto.setEmployeeName(employee.getEmployeeName());
        employeeDto.setDesignation(employee.getDesignation());
        employeeDto.setDepartment(employee.getDepartment());
        employeeDto.setJoiningDate(employee.getJoiningDate());
        employeeDto.setReportingManager(employee.getReportingManager());
        employeeDto.setShiftTiming(employee.getShiftTiming());
        employeeDto.setStatus(employee.getStatus());
        employeeDto.setSalary(employee.getSalary());
        employeeDto.setGender(employee.getGender());
        employeeDto.setAddress(employee.getAddress());
        employeeDto.setPhoneNumber(employee.getPhoneNumber());
        employeeDto.setEmailAddress(employee.getEmailAddress());
        employeeDto.setDateOfBirth(employee.getDateOfBirth());
        employeeDto.setBloodGroup(employee.getUser().getBloodGroup());
        employeeDto.setQualification(employee.getUser().getQualification());
        employeeDto.setSkills(employee.getUser().getSkills());
        employeeDto.setExperience(employee.getUser().getExperience());
        employeeDto.setCvUrl(employee.getUser().getCvUrl());
        employeeDto.setEmergencyContact(employee.getUser().getEmergencyContact());
        employeeDto.setRole(employee.getUser().getRole());
        employeeDto.setProfilePictureUrl(employee.getUser().getProfilePictureUrl());
        employeeDto.setCreatedAt(employee.getUser().getCreatedAt());
        employeeDto.setUpdatedAt(employee.getUser().getUpdatedAt());

        return employeeDto;
    }

    public OrganizationSimpleDto convertOrganizationToSimpleDto(Organization organization) {
        OrganizationSimpleDto dto = new OrganizationSimpleDto();
        dto.setId(organization.getId());
        dto.setOrganizationName(organization.getOrganizationName());
        dto.setOrganizationAddress(organization.getOrganizationAddress());
        dto.setOrganizationEmail(organization.getOrganizationEmail());
        dto.setOrganizationPhone(organization.getOrganizationPhone());
        dto.setOrganizationWebsite(organization.getOrganizationWebsite());
        dto.setOrganizationLogo(organization.getOrganizationLogo());
        dto.setCreatedAt(organization.getCreatedAt());
        dto.setIsActive(organization.getIsActive());
        dto.setCreatorId(organization.getCreatorId());
        return dto;
    }


    public static OrganizationDto convertOrganizationToDto(Organization organization) {
        OrganizationDto dto = new OrganizationDto();
        dto.setId(organization.getId());
        dto.setOrganizationName(organization.getOrganizationName());
        dto.setOrganizationAddress(organization.getOrganizationAddress());
        dto.setOrganizationEmail(organization.getOrganizationEmail());
        dto.setOrganizationPhone(organization.getOrganizationPhone());
        dto.setOrganizationWebsite(organization.getOrganizationWebsite());
        dto.setOrganizationLogo(organization.getOrganizationLogo());
        dto.setCreatedAt(organization.getCreatedAt());
        dto.setEmployees(organization.getEmployees().stream()
                .map(OrganizationDtoConvertor::convertEmployeeToEmployeeDto)
                .collect(Collectors.toList()));
        dto.setProjects(organization.getProjects().stream()
                .map(OrganizationDtoConvertor::convertProjectToProjectDto)
                .collect(Collectors.toList()));
        dto.setIsActive(organization.getIsActive());
        dto.setCreatorId(organization.getCreatorId());
        return dto;
    }

}
