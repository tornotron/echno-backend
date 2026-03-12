package org.tornotron.echno_backend.project;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.DtoConversions.EmployeeDtoConvertor;
import org.tornotron.echno_backend.DtoConversions.ProjectDtoConvertor;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.dto.*;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service class for managing projects.
 * Handles business logic related to project creation, retrieval, updates, and deletion.
 */
@Service
public class ProjectService {

    private static final String PROJECTS_FOLDER = "projects";

    private final ProjectRepository repository;
    private final OrganizationRepository organizationRepository;
    private final EmployeeRepository employeeRepository;
    private final AttachmentService attachmentService;
    private final FileStorageService fileStorageService;

    /**
     * Constructs a ProjectService with the necessary repositories.
     *
     * @param repository             The repository for project data access.
     * @param organizationRepository The repository for organization data access.
     * @param employeeRepository     The repository for employee data access.
     * @param attachmentService      The service for attachment operations.
     */
    public ProjectService(ProjectRepository repository,
                          OrganizationRepository organizationRepository,
                          EmployeeRepository employeeRepository,
                          AttachmentService attachmentService, FileStorageService fileStorageService) {
        this.repository = repository;
        this.organizationRepository = organizationRepository;
        this.employeeRepository = employeeRepository;
        this.attachmentService = attachmentService;
        this.fileStorageService = fileStorageService;
    }

    /**
     * Creates a new project.
     *
     * @param projectDto DTO containing the details for the new project.
     * @return A simple DTO of the newly created project.
     * @throws ResourceNotFoundException if the organization specified in the DTO does not exist.
     */
    @Transactional
    public ProjectSimpleDto addProject(ProjectCreationDto projectDto,List<MultipartFile> attachments) {
            Long orgId = TenantContext.getCurrentOrgId();
            Organization organization = organizationRepository.findById(orgId)
                    .orElseThrow(() -> new ResourceNotFoundException("Organization not found with id: " + orgId));
            if(repository.existsProjectByProjectName(projectDto.getProjectName())){
                throw new DuplicateResourceException("Project with the same name already exists");
            }
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
            
            // Save the project first to get the ID
            Project savedProject = repository.save(project);
            // Upload attachments if provided
            if (attachments != null && !attachments.isEmpty()) {
                List<Attachment> savedAttachments = attachmentService.uploadAttachments(attachments, "PROJECT", savedProject.getId(), PROJECTS_FOLDER);
                for (Attachment attachment : savedAttachments) {
                    savedProject.addAttachment(attachment);
                }
                savedProject = repository.save(savedProject);
            }

            return ProjectDtoConvertor.convertProjectToSimpleDto(savedProject);
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
                .map(project -> ProjectDtoConvertor.convertProjectToDto(project,fileStorageService));
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
        ProjectDto projectDto =repository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .map(project -> ProjectDtoConvertor.convertProjectToDto(project,fileStorageService))
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
    @Transactional
    public ProjectSimpleDto partialUpdateAProject(Map<String,Object> updates, Long id, List<MultipartFile> attachments, String entityType) {
        Project project = repository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: "+id));
        partialUpdateAProject(updates, project);

        if (attachments != null) {
            for(MultipartFile att:attachments) {
                Attachment attachment = attachmentService.uploadAttachment(att,entityType,id,PROJECTS_FOLDER);
                project.addAttachment(attachment);
            }
        }
        return ProjectDtoConvertor.convertProjectToSimpleDto(repository.save(project));
    }

    private void partialUpdateAProject(Map<String, Object> updates, Project project) {
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
                case "projectLongitude":
                    float longitude = Float.parseFloat((String) value);
                    if(longitude >= -180 && longitude <= 180) {
                        project.setProjectLongitude(longitude);
                    } else {
                        throw new IllegalArgumentException("Longitude must be between -180 and 180");
                    }
                    break;
                case "projectLatitude":
                    float latitude = Float.parseFloat((String) value);
                    if (latitude >= -90 && latitude <= 90) {
                        project.setProjectLatitude(latitude);
                    } else {
                        throw new IllegalArgumentException("Latitude must be between -90 and 90");
                    }
                    break;
            }
        });
    }

    /**
     * Updates multiple projects in a batch.
     *
     * @param updates A list of DTOs containing the updates for each project.
     */
    @Transactional
    public void batchUpdateProjects(List<ProjectPatchDto> updates) {
        List<Long> projectIds = updates.stream().map(ProjectPatchDto::getId).collect(Collectors.toList());
        List<Project> projects = repository.findAllById(projectIds);

        Map<Long, Project> projectMap = projects.stream().collect(Collectors.toMap(Project::getId, project -> project));

        updates.forEach(update -> {
            Project project = projectMap.get(update.getId());
            if (project != null) {
                partialUpdateAProject(update.getUpdates(), project);
            }
        });

        repository.saveAll(projects);
    }

    /**
     * Deletes a project by its ID, including all associated attachments.
     *
     * @param id The ID of the project to delete.
     * @throws ResourceNotFoundException if no project with the given ID is found.
     */
    @Transactional
    public void deleteAProject(Long id) {
        if(!repository.existsByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())) {
            throw new ResourceNotFoundException("Project not found with id: "+id);
        }
        // Delete all attachments associated with this project
        attachmentService.deleteAllAttachments("PROJECT", id);
        repository.deleteById(id);
    }

    /**
     * Retrieves the organization ID for a given project ID.
     *
     * @param projectId The ID of the project.
     * @return The ID of the organization to which the project belongs.
     * @throws ResourceNotFoundException if no project with the given ID is found.
     */
    @Transactional(readOnly = true)
    public Long getOrganizationIdByProjectId(Long projectId) {
        Project project = repository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + projectId));
        return project.getOrganization().getId();
    }

    /**
     * Adds an employee to a project.
     *
     * @param projectId  The ID of the project.
     * @param employeeId The ID of the employee to add.
     * @return A list of employee DTOs currently assigned to the project.
     */
    @Transactional
    public List<EmployeeDto> addEmployeeToProject(Long projectId, Long employeeId) {
        Project project = repository.findByIdAndOrganization_Id(projectId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + projectId));
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeId));

        if (project.getEmployees().contains(employee)) {
            throw new DuplicateResourceException("Employee is already assigned to this project");
        }

        project.getEmployees().add(employee);
        repository.save(project);

        return project.getEmployees().stream()
                .map(e -> EmployeeDtoConvertor.convertEmployeeToDto(e, fileStorageService))
                .collect(Collectors.toList());
    }

    /**
     * Removes an employee from a project.
     *
     * @param projectId  The ID of the project.
     * @param employeeId The ID of the employee to remove.
     */
    @Transactional
    public void removeEmployeeFromProject(Long projectId, Long employeeId) {
        Project project = repository.findByIdAndOrganization_Id(projectId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + projectId));
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeId));

        if (!project.getEmployees().remove(employee)) {
            throw new ResourceNotFoundException("Employee with id: " + employeeId + " is not assigned to project with id: " + projectId);
        }

        repository.save(project);
    }

    /**
     * Retrieves all employees assigned to a project.
     *
     * @return A list of employee DTOs assigned to the project.
     */
    @Transactional(readOnly = true)
    public List<ProjectDto> getProjectsByEmployeeId(Long employeeId) {
        employeeRepository.findByIdAndOrganizationId(employeeId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeId));
        return repository.findByEmployees_IdAndOrganization_Id(employeeId, TenantContext.getCurrentOrgId())
                .stream()
                .map(project -> ProjectDtoConvertor.convertProjectToDto(project, fileStorageService))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<EmployeeDto> getEmployeesByProjectId(Long projectId) {
        Project project = repository.findByIdAndOrganization_Id(projectId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + projectId));

        return project.getEmployees().stream()
                .map(e -> EmployeeDtoConvertor.convertEmployeeToDto(e, fileStorageService))
                .collect(Collectors.toList());
    }
}