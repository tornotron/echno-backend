package org.tornotron.echno_backend.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.project.dto.ProjectDto;
import org.tornotron.echno_backend.teamMember.dto.TeamMemberDto;
import org.tornotron.echno_backend.teamMember.TeamMember;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ProjectService {
    private static final Logger logger = LoggerFactory.getLogger(ProjectService.class);
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

    public Boolean addProject(Project project) {
        if(project == null) {
            logger.warn("Attempted to add null project");
            return false;
        }
        try {
            repository.save(project);
            logger.info("Project added successfully: ID={}, NAME={}",project.getId(),project.getProjectName());
            return true;
        } catch (Exception e) {
            logger.error("Data could not be added to database");
            return false;
        }
    }

    public List<ProjectDto> getAllProjects() {
        return repository.findAll().stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    public ProjectDto getAProject(Long id) {
        return repository.findById(id)
                .map(this::convertToDto)
                .orElse(null);

    }

    public boolean updateAProject(Project updatedProject,Long id) {
        Optional<Project> projectOptional = repository.findById(id);
        if(projectOptional.isPresent()) {
            Project projectObj = projectOptional.get();
            projectObj.setProjectName(updatedProject.getProjectName());
            projectObj.setProjectAddress(updatedProject.getProjectAddress());
            return addProject(projectObj);
        }
        return false;
    }

    public boolean deleteAProject(Long id) {
        try {
            repository.deleteById(id);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
