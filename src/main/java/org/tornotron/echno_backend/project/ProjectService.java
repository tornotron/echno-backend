package org.tornotron.echno_backend.project;

import lombok.extern.slf4j.Slf4j;
import org.tornotron.echno_backend.common.payload.PartialUpdateKeys;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.common.conversions.DateConversion;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.project.mapper.ProjectMapper;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.common.history.dto.StatusTransitionDto;
import org.tornotron.echno_backend.common.history.StatusTransitionRepository;
import org.tornotron.echno_backend.common.history.mapper.StatusTransitionMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.compliance.IndianStateResolver;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.finance.ledger.repositories.CustomerRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.common.events.ProjectApprovedEvent;
import org.tornotron.echno_backend.project.dto.*;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;
import org.tornotron.echno_backend.project.enums.ProjectType;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service class for managing projects.
 * Handles business logic related to project creation, retrieval, updates, and deletion.
 */
@Service
@Slf4j
public class ProjectService {

    private static final String PROJECTS_FOLDER = "projects";

    /**
     * Keys the web client puts in a project update that this endpoint has no field for, and drops
     * on purpose rather than noisily. See {@link PartialUpdateKeys} for why the rest are warned
     * about rather than refused.
     *
     * <p>{@code attachments} is the only one: the client sets {@code attachments: []} on every
     * multipart update so the backend can tell "no upload" from "untouched", and the files
     * themselves travel as their own part.
     *
     * <p>{@code organizationId} is deliberately not here even though the client sends it on every
     * update, because it is the one key worth a warning every time. The organization comes from
     * {@code TenantContext}, and honouring a value from the payload would be a tenant-isolation
     * hole. {@code description} and {@code employees} are warned about too: both are on the list
     * in echno-core#57, description as a form field that writes to a concept no layer has, members
     * as something the project-employee routes already do properly.
     */
    private static final Set<String> DELIBERATELY_DROPPED_UPDATE_KEYS = Set.of("attachments");

    /**
     * The state a project starts in when the create payload names none. It is what the web
     * client's create form already defaults to, and what {@code echno-core} documents the server
     * as defaulting to, so making it explicit here settles a contract both sides had assumed.
     */
    private static final ProjectCreationStatus DEFAULT_CREATION_STATUS = ProjectCreationStatus.upcoming;

    /**
     * The kind this module files its status trail under, in the shared {@code status_transition}
     * table. It is the same discriminator the attachment table already uses for a project.
     */
    public static final String HISTORY_ENTITY_TYPE = "PROJECT";

    private final ProjectRepository repository;
    private final OrganizationRepository organizationRepository;
    private final EmployeeRepository employeeRepository;
    private final AttachmentService attachmentService;
    private final ProjectMapper projectMapper;
    private final EmployeeMapper employeeMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final CustomerRepository customerRepository;
    private final PayloadValidator payloadValidator;
    private final UserContextService userContextService;
    private final StatusTransitionRecorder statusTransitionRecorder;
    private final StatusTransitionRepository statusTransitionRepository;
    private final StatusTransitionMapper statusTransitionMapper;

    /**
     * Constructs a ProjectService with the necessary repositories.
     *
     * @param repository             The repository for project data access.
     * @param organizationRepository The repository for organization data access.
     * @param employeeRepository     The repository for employee data access.
     * @param attachmentService      The service for attachment operations.
     * @param customerRepository     The repository used to validate the project's client.
     * @param payloadValidator       Runs the create payload's own constraints.
     * @param userContextService     Names the user behind a write, for the stamps and the trail.
     * @param statusTransitionRecorder Appends to the shared status trail.
     * @param statusTransitionRepository Reads a project's status trail back.
     * @param statusTransitionMapper Maps trail entries to their DTO.
     */
    public ProjectService(ProjectRepository repository,
                          OrganizationRepository organizationRepository,
                          EmployeeRepository employeeRepository,
                          AttachmentService attachmentService, ProjectMapper projectMapper,
                          EmployeeMapper employeeMapper,
                          ApplicationEventPublisher eventPublisher,
                          CustomerRepository customerRepository,
                          PayloadValidator payloadValidator,
                          UserContextService userContextService,
                          StatusTransitionRecorder statusTransitionRecorder,
                          StatusTransitionRepository statusTransitionRepository,
                          StatusTransitionMapper statusTransitionMapper) {
        this.repository = repository;
        this.organizationRepository = organizationRepository;
        this.employeeRepository = employeeRepository;
        this.attachmentService = attachmentService;
        this.projectMapper = projectMapper;
        this.employeeMapper = employeeMapper;
        this.eventPublisher = eventPublisher;
        this.customerRepository = customerRepository;
        this.payloadValidator = payloadValidator;
        this.userContextService = userContextService;
        this.statusTransitionRecorder = statusTransitionRecorder;
        this.statusTransitionRepository = statusTransitionRepository;
        this.statusTransitionMapper = statusTransitionMapper;
    }

    /**
     * Creates a new project.
     *
     * <p>The project starts in the status the payload names, or {@link #DEFAULT_CREATION_STATUS}
     * when it names none. {@code approved} is the one value refused, because approval is a
     * transition and not a starting value: see {@link #requireNotApprovedOnCreate}.
     *
     * @param projectDto DTO containing the details for the new project.
     * @return A simple DTO of the newly created project.
     * @throws InvalidRequestException if the payload asks for a project that is already approved.
     * @throws ResourceNotFoundException if the organization specified in the DTO does not exist.
     */
    @Transactional
    public ProjectSimpleDto addProject(ProjectCreationDto projectDto,List<MultipartFile> attachments) {
            payloadValidator.requireValid(projectDto);
            requireNotApprovedOnCreate(projectDto.getStatus());
            Long orgId = TenantContext.getCurrentOrgId();
            Organization organization = organizationRepository.findById(orgId)
                    .orElseThrow(() -> new ResourceNotFoundException("Organization with ID " + orgId + " was not found"));
            if(repository.existsProjectByProjectName(projectDto.getProjectName())){
                throw new DuplicateResourceException("Project with name '" + projectDto.getProjectName() + "' already exists");
            }
            User actor = userContextService.getCurrentUser();
            LocalDateTime now = LocalDateTime.now();

            Project project = new Project();
            project.setProjectName(projectDto.getProjectName());
            project.setProjectAddress(projectDto.getProjectAddress());
            project.setProjectCity(trimToNull(projectDto.getProjectCity()));
            project.setProjectState(canonicalState(projectDto.getProjectState()));
            project.setProjectPostalCode(trimToNull(projectDto.getProjectPostalCode()));
            project.setCreatedAt(now);
            project.setCreatedBy(actor != null ? actor.getId() : null);
            project.setUpdatedAt(now);
            project.setUpdatedBy(actor != null ? actor.getId() : null);
            project.setProjectLatitude(projectDto.getProjectLatitude());
            project.setProjectLongitude(projectDto.getProjectLongitude());
            project.setStatus(projectDto.getStatus() != null
                    ? projectDto.getStatus()
                    : DEFAULT_CREATION_STATUS);
            if (projectDto.getProjectType() != null && !projectDto.getProjectType().isBlank()) {
                project.setProjectType(ProjectType.valueOf(projectDto.getProjectType()));
            }
            project.setCustomerId(requireCustomerInTenant(projectDto.getCustomerId()));
            project.setOrganization(organization);
            project.setStartDate(projectDto.getStartDate());
            project.setEndDate(projectDto.getEndDate());
            
            // Save the project first to get the ID
            Project savedProject = repository.save(project);

            // The trail opens on the status the project was created in, so a project that was
            // created already holding a status is distinguishable from one patched into it later.
            statusTransitionRecorder.recordCreation(HISTORY_ENTITY_TYPE, savedProject.getId(),
                    organization, savedProject.getStatus().name(), actor);

            // Upload attachments if provided
            if (attachments != null && !attachments.isEmpty()) {
                List<Attachment> savedAttachments = attachmentService.uploadAttachments(attachments, "PROJECT", savedProject.getId(), PROJECTS_FOLDER);
                for (Attachment attachment : savedAttachments) {
                    savedProject.addAttachment(attachment);
                }
                savedProject = repository.save(savedProject);
            }

            return projectMapper.toSimpleDto(savedProject);
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
                .map(project -> projectMapper.toDto(project));
    }

    /**
     * Retrieves a page of projects under an optional free-text filter.
     *
     * <p>Unlike {@link #getAllProjects(int, int)} the caller keeps the {@link Page}, so the total
     * row count and the page index survive to the response and a truncated result says so. Newest
     * first, because a project list is read from the recent end.
     *
     * @param pageNo   Zero-based page index; a negative value is treated as zero.
     * @param pageSize Rows per page, clamped to {@link UnpagedResultCap#MAX_ROWS} so one request
     *                 cannot re-create the unbounded read this endpoint exists to replace.
     * @param search   Optional case-insensitive match on the project name; blank means none.
     * @return A {@link Page} of project DTOs.
     */
    @Transactional(readOnly = true)
    public Page<ProjectDto> getProjectsPaginated(int pageNo, int pageSize, String search) {
        int page = Math.max(pageNo, 0);
        int size = Math.clamp(pageSize, 1, UnpagedResultCap.MAX_ROWS);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        return repository.search(searchPattern(search), pageable)
                .map(project -> projectMapper.toDto(project));
    }

    /**
     * Builds a lower-cased {@code %...%} LIKE pattern for a search term, or null when there is no
     * term to match on. Wildcards the user typed are escaped so a bare {@code %} matches a literal
     * percent sign rather than every row.
     *
     * @param value The raw search term.
     * @return The LIKE pattern, or null when the term is absent or blank.
     */
    private static String searchPattern(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String escaped = value.trim().toLowerCase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
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
                .map(project -> projectMapper.toDto(project))
                .orElse(null);
        if(projectDto==null) {
            throw new ResourceNotFoundException("Project with ID " + id + " was not found in this organization");
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
                .orElseThrow(() -> new ResourceNotFoundException("Project with ID " + id + " was not found in this organization"));
        partialUpdateAProject(updates, project);

        if (attachments != null) {
            for(MultipartFile att:attachments) {
                Attachment attachment = attachmentService.uploadAttachment(att,entityType,id,PROJECTS_FOLDER);
                project.addAttachment(attachment);
            }
        }
        return projectMapper.toSimpleDto(repository.save(project));
    }

    private void partialUpdateAProject(Map<String, Object> updates, Project project) {
        ProjectCreationStatus previousStatus = project.getStatus();

        updates.forEach((key,value) -> {
            switch (key) {
                case "projectName":
                    project.setProjectName((String) value);
                    break;
                case "projectAddress":
                    project.setProjectAddress((String) value);
                    break;
                case "projectCity":
                    project.setProjectCity(trimToNull((String) value));
                    break;
                case "projectState":
                    project.setProjectState(canonicalState((String) value));
                    break;
                case "projectPostalCode":
                    project.setProjectPostalCode(trimToNull((String) value));
                    break;
                case "status":
                    project.setStatus(ProjectCreationStatus.valueOf((String) value));
                    break;
                case "startDate":
                    project.setStartDate(DateConversion.parseLocalDateTime(value));
                    break;
                case "endDate":
                    project.setEndDate(DateConversion.parseLocalDateTime(value));
                    break;
                case "projectType":
                    project.setProjectType(value != null ? ProjectType.valueOf((String) value) : null);
                    break;
                case "customerId":
                    // Sent as a string over JSON; a null or blank value clears the client.
                    String customerId = value != null ? value.toString().trim() : null;
                    project.setCustomerId(customerId == null || customerId.isEmpty()
                            ? null
                            : requireCustomerInTenant(UUID.fromString(customerId)));
                    break;
                case "projectLongitude":
                    float longitude = ((Number) value).floatValue();
                    if(longitude >= -180 && longitude <= 180) {
                        project.setProjectLongitude(longitude);
                    } else {
                        throw new IllegalArgumentException("Longitude must be between -180 and 180");
                    }
                    break;
                case "projectLatitude":
                    float latitude = ((Number) value).floatValue();
                    if (latitude >= -90 && latitude <= 90) {
                        project.setProjectLatitude(latitude);
                    } else {
                        throw new IllegalArgumentException("Latitude must be between -90 and 90");
                    }
                    break;
                default:
                    PartialUpdateKeys.reportUnknown(log, "project", project.getId(), key,
                            DELIBERATELY_DROPPED_UPDATE_KEYS);
                    break;
            }
        });

        onApproval(project, previousStatus);

        // Resolved once: both the trail entry and the stamp name the same user, and looking the
        // user up costs a query.
        User actor = userContextService.getCurrentUser();
        recordStatusChange(project, previousStatus, actor);
        stampWrite(project, actor);
    }

    /**
     * Appends a status change to the project's trail, if the patch was one.
     *
     * <p>Runs after {@link #onApproval}, so an approval the project's state does not allow is
     * refused before anything is written. It is not conditional on the transition being an
     * approval: approval is the transition that carries behaviour, but a project moved to
     * {@code onHold} or {@code cancelled} raises the same question of who did it and when.
     *
     * <p>{@link StatusTransitionRecorder#recordChange} writes nothing when the two statuses are
     * equal, so a patch that touched only the address leaves the trail alone.
     *
     * @param project        The project the patch has been applied to.
     * @param previousStatus The status it held before the patch.
     * @param actor          The user making the change, or null where there was no user context.
     */
    private void recordStatusChange(Project project, ProjectCreationStatus previousStatus, User actor) {
        statusTransitionRecorder.recordChange(
                HISTORY_ENTITY_TYPE,
                project.getId(),
                project.getOrganization(),
                previousStatus != null ? previousStatus.name() : null,
                project.getStatus() != null ? project.getStatus().name() : null,
                actor,
                null);
    }

    /**
     * Stamps who wrote the project and when.
     *
     * <p>This is the last write and nothing more: the next patch overwrites it, so it answers
     * "when did this last change, and by whom" and never "who approved it". That second question
     * belongs to the status trail, which is why both exist.
     *
     * <p>Called from every path that saves a project, the team changes included. A stamp that
     * covered only the field patch would report a project as untouched since last month while its
     * team was rebuilt yesterday, which is worse than no stamp: it is a wrong answer to the one
     * question it exists to answer.
     *
     * @param project The project being written.
     * @param actor   The user making the change, or null where there was no user context.
     */
    private void stampWrite(Project project, User actor) {
        project.setUpdatedAt(LocalDateTime.now());
        project.setUpdatedBy(actor != null ? actor.getId() : null);
    }

    /**
     * Reads a project's status trail, newest first.
     *
     * <p>Entries begin where recording began. A project created before the trail existed carries
     * a single {@code BASELINE} entry naming the status it was observed to hold at that moment,
     * with no actor and no earlier status, because there is nothing else that can truthfully be
     * said about a history nobody recorded.
     *
     * @param id       The project whose trail to read.
     * @param pageNo   Zero-based page index.
     * @param pageSize Entries per page.
     * @return A page of trail entries, newest first.
     * @throws ResourceNotFoundException if no project with the given ID is found in this organization.
     */
    @Transactional(readOnly = true)
    public Page<StatusTransitionDto> getStatusHistory(Long id, int pageNo, int pageSize) {
        Long orgId = TenantContext.getCurrentOrgId();
        if (!repository.existsByIdAndOrganization_Id(id, orgId)) {
            throw new ResourceNotFoundException(
                    "Project with ID " + id + " was not found in this organization");
        }
        return statusTransitionRepository
                .findByEntityTypeAndEntityIdAndOrganization_IdOrderByOccurredAtDescIdDesc(
                        HISTORY_ENTITY_TYPE, id, orgId, PageRequest.of(pageNo, pageSize))
                .map(statusTransitionMapper::toDto);
    }


    /**
     * Runs the approval transition, once the whole patch has been applied.
     *
     * <p>Approval is what publishes {@link ProjectApprovedEvent}, and only on the transition INTO
     * approved, never on a save that leaves a project already approved. Because it runs after the
     * loop rather than inside it, a single patch that sets the state and the status together is
     * judged on the state it is setting, whichever order the two arrive in.
     *
     * @param project The project the patch has been applied to.
     * @param previousStatus The status it held before the patch.
     */
    private void onApproval(Project project, ProjectCreationStatus previousStatus) {
        if (project.getStatus() != ProjectCreationStatus.approved
                || previousStatus == ProjectCreationStatus.approved) {
            return;
        }

        requireStateForApproval(project);

        Long orgId = project.getOrganization() != null ? project.getOrganization().getId() : null;
        // The listener runs AFTER_COMMIT, so the row is durable before the AI flow reads it.
        eventPublisher.publishEvent(new ProjectApprovedEvent(this, project.getId(), orgId));
    }

    /**
     * Refuses a project asked to be created already approved.
     *
     * <p>Approval is the only project transition with anything behind it. {@link #onApproval}
     * checks that the project's state is known and publishes {@link ProjectApprovedEvent}, which
     * is what draws up the project's compliance inspections. Both run on the patch path, so a
     * create that wrote {@code approved} straight onto the row skipped them, and no later patch
     * could make up for it: {@link #onApproval} fires only on the transition INTO approved, and a
     * project that was born approved never makes that transition. The project stays approved for
     * good with no compliance work ever drawn up, and its only outward sign is an absence.
     *
     * <p>Every other status is a label with no transition behind it, so create still accepts them
     * all. This is the same rule {@code PurchaseOrderService.createPurchaseOrder} applies: a
     * create may not reach a state whose transition carries a check or an event; the set of
     * states that leaves is whatever each entity's state machine happens to gate.
     *
     * @param status The status the create payload asked for, or null when it asked for none.
     * @throws InvalidRequestException if that status is {@code approved}.
     */
    private void requireNotApprovedOnCreate(ProjectCreationStatus status) {
        if (status == ProjectCreationStatus.approved) {
            throw new InvalidRequestException(
                    "A project cannot be created already approved. Approval draws up the project's "
                            + "compliance inspections from the regulations of the state it is built "
                            + "in, and a project created as approved never makes that transition, so "
                            + "those inspections would never be drawn up and nothing later could put "
                            + "it right. Create the project, then approve it with "
                            + "PATCH /projects/{id}.");
        }
    }

    /**
     * Refuses an approval the compliance generation behind it could not act on.
     *
     * <p>The state is optional while a project is being drafted, because site paperwork is often
     * settled after the project record exists. Approval is where it stops being optional: it is
     * the moment that generates the project's compliance inspections, and those are keyed by the
     * state's regulations. Left unchecked, the project is approved, the generation fails out of
     * sight in an AFTER_COMMIT listener, and the missing field surfaces as an absence of
     * inspections rather than as a message.
     *
     * <p>The address scan still counts, so a project that predates the state field but names its
     * state in its address line approves exactly as it did before.
     *
     * @param project The project being approved.
     * @throws InvalidRequestException if neither the state field nor the address yields a state.
     */
    private void requireStateForApproval(Project project) {
        String state = IndianStateResolver.forProject(
                project.getProjectState(), project.getProjectAddress());
        if (state == null) {
            throw new InvalidRequestException(
                    "This project cannot be approved without a state. Approval draws up the "
                            + "project's compliance inspections from the regulations of the state it "
                            + "is built in, and neither the project's state nor its address names "
                            + "one. Set the project's state (for example Tamil Nadu) and approve it "
                            + "again.");
        }
    }

    /**
     * Trims a supplied text field, treating an empty or whitespace-only value as absent rather
     * than storing a blank. Optional address parts are read as "recorded or not", so a blank
     * and a missing value have to mean the same thing.
     *
     * @param value The value as supplied, possibly null.
     * @return The trimmed value, or null when there is nothing in it.
     */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Normalises a supplied state to its canonical spelling before it is stored. Compliance
     * rules are looked up by state name, so a state stored as the user happened to type it
     * would match nothing; and a state that is not a state at all is worth refusing at the
     * point of entry rather than leaving to fail silently at generation time.
     *
     * @param state The state name as supplied, possibly null or blank.
     * @return The canonical state name, or null when none was given.
     * @throws InvalidRequestException if the name is not an Indian state or union territory.
     */
    private String canonicalState(String state) {
        try {
            return IndianStateResolver.canonicalise(state);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(e.getMessage());
        }
    }

    /**
     * Checks that a project's client exists in the current tenant before it is stored on the
     * project. A null id means the project has no client and is returned unchanged.
     *
     * @param customerId The finance customer id to validate, or null.
     * @return The same id, once it is known to resolve in this tenant.
     * @throws ResourceNotFoundException if the id does not resolve to a customer in this tenant.
     */
    private UUID requireCustomerInTenant(UUID customerId) {
        if (customerId == null) {
            return null;
        }
        if (customerRepository.findScopedById(customerId).isEmpty()) {
            throw new ResourceNotFoundException(
                    "Customer with ID " + customerId + " was not found in this organization");
        }
        return customerId;
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
            throw new ResourceNotFoundException("Project with ID " + id + " was not found in this organization");
        }
        // Delete all attachments associated with this project
        attachmentService.deleteAllAttachments("PROJECT", id);
        repository.deleteByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId());
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
        Project project = repository.findByIdAndOrganization_Id(projectId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Project with ID " + projectId + " was not found in this organization"));
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
                .orElseThrow(() -> new ResourceNotFoundException("Project with ID " + projectId + " was not found in this organization"));
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + employeeId + " was not found in this organization"));

        if (project.getEmployees().contains(employee)) {
            throw new DuplicateResourceException("Employee with ID " + employeeId + " is already assigned to project with ID " + projectId);
        }

        project.getEmployees().add(employee);
        // Adding somebody to the team is a change to the project, so it stamps like any other.
        stampWrite(project, userContextService.getCurrentUser());
        repository.save(project);

        return project.getEmployees().stream()
                .map(e -> employeeMapper.toDto(e))
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
                .orElseThrow(() -> new ResourceNotFoundException("Project with ID " + projectId + " was not found in this organization"));
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + employeeId + " was not found in this organization"));

        if (!project.getEmployees().remove(employee)) {
            throw new ResourceNotFoundException("Employee with ID " + employeeId + " is not assigned to project with ID " + projectId);
        }

        stampWrite(project, userContextService.getCurrentUser());
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
                .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + employeeId + " was not found in this organization"));
        return repository.findByEmployees_IdAndOrganization_Id(employeeId, TenantContext.getCurrentOrgId())
                .stream()
                .map(project -> projectMapper.toDto(project))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<EmployeeDto> getEmployeesByProjectId(Long projectId) {
        Project project = repository.findByIdAndOrganization_Id(projectId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Project with ID " + projectId + " was not found in this organization"));

        return project.getEmployees().stream()
                .map(e -> employeeMapper.toDto(e))
                .collect(Collectors.toList());
    }
}