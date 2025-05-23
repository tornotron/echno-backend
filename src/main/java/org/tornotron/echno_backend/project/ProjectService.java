package org.tornotron.echno_backend.project;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.dto.ProjectCreationDto;
import org.tornotron.echno_backend.project.dto.ProjectDto;
import org.tornotron.echno_backend.project.dto.ProjectPatchDto;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;
import org.tornotron.echno_backend.teamMember.dto.TeamMemberDto;
import org.tornotron.echno_backend.teamMember.TeamMember;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ProjectService {
    private final ProjectRepository repository;
    private final OrganizationRepository organizationRepository;

    public ProjectService(ProjectRepository repository, OrganizationRepository organizationRepository) {
        this.repository = repository;
        this.organizationRepository = organizationRepository;
    }

    private TeamMemberDto convertTeamMemberToTeamMemberDTO(TeamMember teamMember) {
        TeamMemberDto teamMemberDto = new TeamMemberDto();
        teamMemberDto.setId(teamMember.getId());
        teamMemberDto.setMemberName(teamMember.getMemberName());
        teamMemberDto.setMemberEmail(teamMember.getMemberEmail());
        teamMemberDto.setMemberPhone(teamMember.getMemberPhone());
        teamMemberDto.setMemberRole(teamMember.getMemberRole());
        teamMemberDto.setMemberImage(teamMember.getMemberImage());
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
            Organization organization = organizationRepository.findOrganizationByOrganizationName(projectDto.getOrganizationName());
            Project project = new Project();
            project.setProjectName(projectDto.getProjectName());
            project.setProjectAddress(projectDto.getProjectAddress());
            project.setCreatedAt(LocalDateTime.now());
            project.setStatus(ProjectCreationStatus.valueOf(projectDto.getStatus()));
            project.setOrganization(organization);
            Project savedProject = repository.save(project);
            if(savedProject.getId() == null) {
                throw new DatabaseOperationException("Project could not be created");
            }
    }

    public Page<ProjectDto> getAllProjects(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC,"id"));
        return repository.findAll(pageable)
                .map(this::convertToDto);
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

    public void partialUpdateAProject(Map<String,Object> updates,Long id) {
        Project project = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: "+id));

        updates.forEach((key,value) -> {
            switch (key) {
                case "projectName":
                    project.setProjectName((String) value);
                    break;
                case "projectAddress":
                    project.setProjectAddress((String) value);
                    break;
                case "status":
                    project.setStatus(ProjectCreationStatus.valueOf((String) value));
                    break;
            }
        });
        repository.save(project);
    }

    public void batchUpdateProjects(List<ProjectPatchDto> updates) {
        updates.forEach(update ->
                partialUpdateAProject(update.getUpdates(), update.getId()));
    }

    public void deleteAProject(Long id) {
        if(!repository.existsById(id)) {
            throw new ResourceNotFoundException("Project not found with id: "+id);
        }
        repository.deleteById(id);
    }
}
