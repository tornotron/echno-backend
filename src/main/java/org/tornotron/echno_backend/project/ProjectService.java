package org.tornotron.echno_backend.project;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.ProjectDtoConvertor;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.dto.ProjectCreationDto;
import org.tornotron.echno_backend.project.dto.ProjectDto;
import org.tornotron.echno_backend.project.dto.ProjectPatchDto;
import org.tornotron.echno_backend.project.dto.ProjectSimpleDto;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Service class for managing projects.
 * Handles business logic related to project creation, retrieval, updates, and deletion.
 */
@Service
@Transactional
public class ProjectService {
    private final ProjectRepository repository;
    private final OrganizationRepository organizationRepository;

    /**
     * Constructs a ProjectService with the necessary repositories.
     *
     * @param repository             The repository for project data access.
     * @param organizationRepository The repository for organization data access.
     */
    public ProjectService(ProjectRepository repository, OrganizationRepository organizationRepository) {
        this.repository = repository;
        this.organizationRepository = organizationRepository;
    }

    /**
     * Creates a new project.
     *
     * @param projectDto DTO containing the details for the new project.
     * @return A simple DTO of the newly created project.
     * @throws ResourceNotFoundException if the organization specified in the DTO does not exist.
     */
    public ProjectSimpleDto addProject(ProjectCreationDto projectDto) {
            Organization organization = organizationRepository.findById(projectDto.getOrganizationId())
                    .orElseThrow(() -> new ResourceNotFoundException("Organization not found with id: " + projectDto.getOrganizationId()));
            Project project = new Project();
            project.setProjectName(projectDto.getProjectName());
            project.setProjectAddress(projectDto.getProjectAddress());
            project.setCreatedAt(LocalDateTime.now());
            project.setProjectLatitude(projectDto.getProjectLatitude());
            project.setProjectLongitude(projectDto.getProjectLongitude());
            project.setStatus(ProjectCreationStatus.valueOf(projectDto.getStatus()));
            project.setOrganization(organization);
            project.setStartDate(projectDto.getStartDate());
            project.setEndDate(projectDto.getEndDate());
            return ProjectDtoConvertor.convertProjectToSimpleDto(repository.save(project));
    }

    /**
     * Retrieves a paginated list of all projects.
     *
     * @param pageNo   The page number to retrieve.
     * @param pageSize The number of projects per page.
     * @return A {@link Page} of project DTOs.
     */
    @Transactional(readOnly = true)
    public Page<ProjectDto> getAllProjects(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC,"id"));
        return repository.findAll(pageable)
                .map(ProjectDtoConvertor::convertProjectToDto);
    }

    /**
     * Retrieves a single project by its ID.
     *
     * @param id The ID of the project to retrieve.
     * @return The project DTO.
     * @throws ResourceNotFoundException if no project with the given ID is found.
     */
    @Transactional(readOnly = true)
    public ProjectDto getAProject(Long id) {
        ProjectDto projectDto =repository.findById(id)
                .map(ProjectDtoConvertor::convertProjectToDto)
                .orElse(null);
        if(projectDto==null) {
            throw new ResourceNotFoundException("Project not found with id: "+id);
        } else {
            return projectDto;
        }

    }

    /**
     * Partially updates an existing project.
     *
     * @param updates A map of fields to update.
     * @param id      The ID of the project to update.
     * @throws ResourceNotFoundException if no project with the given ID is found.
     */
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

    /**
     * Updates multiple projects in a batch.
     *
     * @param updates A list of DTOs containing the updates for each project.
     */
    public void batchUpdateProjects(List<ProjectPatchDto> updates) {
        updates.forEach(update ->
                partialUpdateAProject(update.getUpdates(), update.getId()));
    }

    /**
     * Deletes a project by its ID.
     *
     * @param id The ID of the project to delete.
     * @throws ResourceNotFoundException if no project with the given ID is found.
     */
    public void deleteAProject(Long id) {
        if(!repository.existsById(id)) {
            throw new ResourceNotFoundException("Project not found with id: "+id);
        }
        repository.deleteById(id);
    }
}