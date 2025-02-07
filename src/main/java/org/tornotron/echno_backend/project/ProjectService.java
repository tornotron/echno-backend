package org.tornotron.echno_backend.project;

import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.project.dto.ProjectCreationDto;
import org.tornotron.echno_backend.project.dto.ProjectDto;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;
import org.tornotron.echno_backend.teamMember.dto.TeamMemberDto;
import org.tornotron.echno_backend.teamMember.TeamMember;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ProjectService {
    private final ProjectRepository repository;

    public ProjectService(ProjectRepository repository) {
        this.repository = repository;
    }

    private TeamMemberDto convertTeamMemberToTeamMemberDTO(TeamMember teamMember) {
        TeamMemberDto teamMemberDto = new TeamMemberDto();
        teamMemberDto.setId(teamMember.getId());
        teamMemberDto.setMemberName(teamMember.getMemberName());
        teamMemberDto.setMemberEmail(teamMember.getMemberEmail());
        return teamMemberDto;
    }

    private ProjectDto convertToDto(Project project) {
        ProjectDto dto = new ProjectDto();
        dto.setId(project.getId());
        dto.setProjectName(project.getProjectName());
        dto.setProjectAddress(project.getProjectAddress());
        dto.setStatus(project.getStatus());
        dto.setCreatedAt(project.getCreatedAt());
        dto.setTeamMembers(project.getTeamMembers().stream()
                .map(this::convertTeamMemberToTeamMemberDTO)
                .collect(Collectors.toList()));
        return dto;
    }

    public void addProject(ProjectCreationDto projectDto) {
            Project project = new Project();
            project.setProjectName(projectDto.getProjectName());
            project.setProjectAddress(projectDto.getProjectAddress());
            project.setCreatedAt(projectDto.getCreatedAt());
            project.setStatus(ProjectCreationStatus.valueOf(projectDto.getStatus()));
            Project savedProject = repository.save(project);
            if(savedProject.getId() == null) {
                throw new DatabaseOperationException("Project could not be created");
            }
    }

    public List<ProjectDto> getAllProjects() {
        return repository.findAll().stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    public ProjectDto getAProject(Long id) {
        ProjectDto projectDto =repository.findById(id)
                .map(this::convertToDto)
                .orElse(null);
        if(projectDto==null) {
            throw new ResourceNotFoundException("Project not found with id: "+id);
        } else {
            return projectDto;
        }

    }

    public void updateAProject(Project updatedProject,Long id) {
        Optional<Project> projectOptional = repository.findById(id);
        if(projectOptional.isPresent()) {
            Project projectObj = projectOptional.get();
            projectObj.setProjectName(updatedProject.getProjectName());
            projectObj.setProjectAddress(updatedProject.getProjectAddress());
            Project savedProject = repository.save(projectObj);
            if(savedProject.getId() == null) {
                throw new DatabaseOperationException("Project could not be updated");
            }
        }
        throw new ResourceNotFoundException("Project not found with id: "+id);
    }

    public void deleteAProject(Long id) {
        if(!repository.existsById(id)) {
            throw new ResourceNotFoundException("Project not found with id: "+id);
        }
        repository.deleteById(id);
    }
}
