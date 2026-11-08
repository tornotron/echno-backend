package org.tornotron.echno_backend.issue;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tornotron.echno_backend.common.payload.PartialUpdateKeys;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.issue.mapper.IssueMapper;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.issue.dto.IssueCreationDto;
import org.tornotron.echno_backend.issue.dto.IssueDto;
import org.tornotron.echno_backend.issue.dto.IssueSimpleDto;
import org.tornotron.echno_backend.issue.dto.IssueStatsDto;
import org.tornotron.echno_backend.issue.enums.IssuePriority;
import org.tornotron.echno_backend.issue.enums.IssueStatus;
import org.tornotron.echno_backend.issue.enums.IssueType;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.task.TaskRepository;
import org.tornotron.echno_backend.category.Category;
import org.tornotron.echno_backend.category.CategoryRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class IssueService {

    private static final Logger log = LoggerFactory.getLogger(IssueService.class);

    private static final String ISSUES_FOLDER = "issues";

    /**
     * Keys the web client puts in an issue update that this endpoint has no field for, and drops
     * on purpose rather than noisily. See {@link PartialUpdateKeys} for why the rest are warned
     * about rather than refused.
     *
     * <p>{@code attachments} is the client telling the difference between "no upload" and
     * "untouched": it sets {@code attachments: []} on every update, and the files themselves
     * travel as their own multipart part, so the key in the JSON part carries nothing to apply.
     * It is the only one left. {@code priority} was here for the same reason until the entity
     * grew the column, and the update now applies it.
     *
     * <p>Everything else falls to the {@code default} branch and is logged, because a key nobody
     * declared is how the {@code issueType} bug stayed invisible: the update answered 200 and
     * changed nothing. Naming the known one here is what keeps that warning worth reading. It is
     * on the list in echno-core#57, and this set shrinks as that list is worked down.
     */
    private static final Set<String> DELIBERATELY_DROPPED_UPDATE_KEYS = Set.of("attachments");

    /**
     * The state an issue starts in, and the only one create accepts. It is what the web client's
     * issue form already defaults to.
     */
    private static final IssueStatus CREATION_STATUS = IssueStatus.open;

    private final IssueRepository issueRepository;
    private final TaskRepository taskRepository;
    private final AttachmentService attachmentService;
    private final IssueMapper issueMapper;
    private final EmployeeRepository employeeRepository;
    private final CurrentEmployeeService currentEmployeeService;
    private final PayloadValidator payloadValidator;
    private final ProjectRepository projectRepository;
    private final CategoryRepository categoryRepository;

    /**
     * Constructs an IssueService with the necessary collaborators.
     *
     * @param issueRepository    The repository for issue data access.
     * @param taskRepository     The repository used to resolve the task an issue is raised against.
     * @param attachmentService  The service for attachment operations.
     * @param issueMapper        The mapper between issues and their DTOs.
     * @param employeeRepository The repository used to resolve the assignee.
     * @param currentEmployeeService Resolves the caller to the employee an issue is recorded as
     *                           having been raised by.
     * @param payloadValidator   Runs the create payload's own constraints.
     * @param projectRepository  Resolves the project an issue belongs to.
     * @param categoryRepository Resolves the work category that is an issue's domain.
     */
    public IssueService(IssueRepository issueRepository, TaskRepository taskRepository, AttachmentService attachmentService, IssueMapper issueMapper, EmployeeRepository employeeRepository, CurrentEmployeeService currentEmployeeService, PayloadValidator payloadValidator, ProjectRepository projectRepository, CategoryRepository categoryRepository) {
        this.issueRepository = issueRepository;
        this.taskRepository = taskRepository;
        this.attachmentService = attachmentService;
        this.issueMapper = issueMapper;
        this.employeeRepository = employeeRepository;
        this.currentEmployeeService = currentEmployeeService;
        this.payloadValidator = payloadValidator;
        this.projectRepository = projectRepository;
        this.categoryRepository = categoryRepository;
    }

    /**
     * Creates a new issue, with any attachments that came with it.
     *
     * <p>The task is optional, but the issue has to belong to a project or a domain (a work
     * category), the rule the product owner settled on 8 Nov 2026 (ClickUp 14zdkkvzx60): see
     * {@link #applyLinks}.
     *
     * <p>The issue starts {@link IssueStatus#open}, whether the payload says so or says nothing,
     * and any other starting value is refused: see {@link #requireCreatableStatus}.
     *
     * <p>Who raised it is the signed-in caller, not a {@code createdById} on the payload. The
     * endpoint is open to every member of the tenant, so a creator id the caller sent could only
     * ever be checked for naming some employee of that tenant, and an issue "raised by" a site
     * engineer is read as their report. See
     * {@link org.tornotron.echno_backend.common.service.CurrentEmployeeService}.
     *
     * @param issueCreationDto DTO containing the details for the new issue.
     * @param attachments      Files uploaded with the issue, or null when there are none.
     * @return The created issue.
     * @throws ConstraintViolationException if the payload fails its own constraints.
     * @throws InvalidRequestException if the payload asks for a status other than open, names
     *                                   neither a task, a project nor a domain, or names a project
     *                                   other than its task's.
     * @throws org.springframework.security.access.AccessDeniedException if the caller has no
     *                                   employee record in this organization.
     * @throws ResourceNotFoundException if the task, project, domain or assignee is not found in
     *                                   this organization.
     */
    @Transactional
    public IssueSimpleDto addIssue(IssueCreationDto issueCreationDto, List<MultipartFile> attachments) {
        payloadValidator.requireValid(issueCreationDto);
        requireCreatableStatus(issueCreationDto.getStatus());
        Employee creator = currentEmployeeService.requireCurrentEmployee("raise an issue");
        Issue issue = new Issue();
        issue.setTitle(issueCreationDto.getTitle());
        issue.setDescription(issueCreationDto.getDescription());
        issue.setType(parseIssueType(issueCreationDto.getType()));
        issue.setStatus(CREATION_STATUS);
        issue.setPriority(issueCreationDto.getPriority());
        issue.setCreatedBy(creator);
        applyLinks(issue, resolveTask(issueCreationDto.getTaskId()),
                resolveProject(issueCreationDto.getProjectId()),
                resolveCategory(issueCreationDto.getCategoryId()));
        issue.setOrganization(organizationOf(issue, creator));

        if (issueCreationDto.getAssignedToId() != null) {
            Employee assignee = employeeRepository.findByIdAndOrganizationId(issueCreationDto.getAssignedToId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Assignee (employee) with ID " + issueCreationDto.getAssignedToId() + " was not found in this organization"));
            issue.setAssignedTo(assignee);
        }

        Issue savedIssue = issueRepository.save(issue);

        if (attachments != null && !attachments.isEmpty()) {
            List<Attachment> savedAttachments = attachmentService.uploadAttachments(attachments, "ISSUE", savedIssue.getId(), ISSUES_FOLDER);
            for(Attachment attachment : savedAttachments) {
                savedIssue.addAttachment(attachment);
            }
            savedIssue = issueRepository.save(savedIssue);
        }

        return issueMapper.toSimpleDto(savedIssue);
    }


    @Transactional(readOnly = true)
    public Page<IssueDto> getAllIssuesPaginated(int pageNo, int pageSize, Long projectId, String search, String status, String type, Long assigneeId, Long creatorId) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return issueRepository.search(projectId, searchPattern(search), parseStatus(status), parseType(type), assigneeId, creatorId, pageable)
                .map(issue -> issueMapper.toDto(issue));
    }

    @Transactional(readOnly = true)
    public IssueStatsDto getIssueStats(Long projectId, String search, String type) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        long total = 0;
        for (Object[] row : issueRepository.countByStatus(projectId, searchPattern(search), parseType(type))) {
            IssueStatus status = (IssueStatus) row[0];
            long count = (Long) row[1];
            byStatus.put(status.name(), count);
            total += count;
        }
        return new IssueStatsDto(total, byStatus);
    }

    /**
     * Builds a lower-cased {@code %...%} LIKE pattern for the search term, or null
     * when blank. The pattern is assembled here rather than with SQL {@code CONCAT}
     * so no null bind lands inside a {@code ||}, which CockroachDB mistypes as bytes.
     */
    private static String searchPattern(String value) {
        return (value == null || value.isBlank()) ? null : "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    // Optional filter parsers: a blank value means "no filter".
    private static IssueStatus parseStatus(String status) {
        return (status == null || status.isBlank()) ? null : parseIssueStatus(status);
    }

    private static IssueType parseType(String type) {
        return (type == null || type.isBlank()) ? null : parseIssueType(type);
    }

    /**
     * Sets what an issue is about, and refuses a combination the rule does not allow.
     *
     * <p>The rule (ClickUp 14zdkkvzx60): an issue can be raised without a task, but it has to be
     * connected to a project or to a domain. The domain is a work category, the list a task is
     * classified under (Concrete Works, Masonry Works and the rest of the standard list, plus any
     * an organization adds), so "is it about concrete, masonry, structure" has the same answer
     * on an issue as on the work it concerns.
     *
     * <ul>
     *   <li>With a task, the issue takes the task's project, and the task's work category as its
     *       domain unless another is named. A project that is not the task's is refused.</li>
     *   <li>Without a task, a project or a domain (or both) is required.</li>
     * </ul>
     *
     * @param issue    The issue being written.
     * @param task     The task it is raised on, or null.
     * @param project  The project named, or null.
     * @param category The domain named, or null.
     * @throws InvalidRequestException if the combination breaks the rule.
     */
    private static void applyLinks(Issue issue, Task task, Project project, Category category) {
        if (task != null) {
            Project taskProject = task.getProject();
            if (project != null && taskProject != null && !project.getId().equals(taskProject.getId())) {
                throw new InvalidRequestException("Task " + task.getId() + " belongs to project "
                        + taskProject.getId() + ", not project " + project.getId()
                        + ". Leave the project out, or name the task's own project.");
            }
            project = taskProject;
            if (category == null) {
                category = task.getCategory();
            }
        } else if (project == null && category == null) {
            throw new InvalidRequestException("An issue has to be connected to a project or a domain. "
                    + "Name the project it belongs to, the work category it concerns (for example "
                    + "Concrete Works or Masonry Works), or the task it was found on.");
        }
        issue.setTask(task);
        issue.setProject(project);
        issue.setCategory(category);
    }

    /**
     * The organization the issue is filed under: that of what it is linked to, which every lookup
     * above has already held to the caller's tenant, falling back to the caller's own.
     */
    private static org.tornotron.echno_backend.organization.Organization organizationOf(Issue issue, Employee creator) {
        if (issue.getTask() != null && issue.getTask().getOrganization() != null) {
            return issue.getTask().getOrganization();
        }
        if (issue.getProject() != null && issue.getProject().getOrganization() != null) {
            return issue.getProject().getOrganization();
        }
        if (issue.getCategory() != null && issue.getCategory().getOrganization() != null) {
            return issue.getCategory().getOrganization();
        }
        return creator.getOrganization();
    }

    private Task resolveTask(Long taskId) {
        if (taskId == null) {
            return null;
        }
        return taskRepository.findByIdAndOrganization_Id(taskId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Task with ID " + taskId + " was not found in this organization"));
    }

    private Project resolveProject(Long projectId) {
        if (projectId == null) {
            return null;
        }
        return projectRepository.findByIdAndOrganization_Id(projectId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project with ID " + projectId + " was not found in this organization"));
    }

    private Category resolveCategory(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        return categoryRepository.findByIdAndOrganization_Id(categoryId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Work category with ID " + categoryId + " was not found in this organization"));
    }

    /**
     * Reads one of the link keys of a partial issue update: a number, or null to clear the link.
     */
    private static Long readLinkId(Object value, String key) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new InvalidRequestException(key + " must be a number");
        }
        return number.longValue();
    }

    /**
     * Parses a required issue type. A missing or unknown value is a client error
     * (400) rather than the {@code NullPointerException}/500 that a bare
     * {@code IssueType.valueOf} would raise — the create/update DTOs are parsed
     * from a multipart string part, so their {@code @NotNull} is not enforced.
     */
    private static IssueType parseIssueType(String type) {
        if (type == null || type.isBlank()) {
            throw new InvalidRequestException("Issue type is required");
        }
        try {
            return IssueType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("'" + type + "' is not a valid issue type");
        }
    }

    /**
     * Refuses an issue asked to be created in any state but {@link #CREATION_STATUS}.
     *
     * <p>Raising an issue is open to every member of the tenant
     * ({@code IssueControllerWeb.createIssue}), while changing one is not: the patch endpoint
     * wants {@code system-admin} or {@code project-manager}. A create that copied the payload's
     * status therefore handed every member the moves that gate was written to withhold, and
     * {@code resolved} and {@code closed} most of all, since an issue nobody with the authority to
     * close it ever closed reads exactly like one that was properly dealt with.
     *
     * <p>This is the rule {@code PurchaseOrderService.createPurchaseOrder} applies: a create may
     * not reach a state whose transition carries a check or an event. Here the patch endpoint
     * gates every transition, so {@code open} is all that is left.
     *
     * @param status The status the create payload asked for, or null when it asked for none.
     * @throws InvalidRequestException if that status is anything but {@link #CREATION_STATUS}.
     */
    private void requireCreatableStatus(IssueStatus status) {
        if (status != null && status != CREATION_STATUS) {
            throw new InvalidRequestException(
                    "An issue is raised as " + CREATION_STATUS + " and cannot be created as "
                            + status + ". Raise it first, then move it with the issue update "
                            + "endpoint, which is where the authority to change an issue's status "
                            + "is checked.");
        }
    }

    /**
     * Reads the {@code priority} key of a partial issue update.
     *
     * <p>Where {@link #parseIssueType} and {@link #parseIssueStatus} refuse a missing value,
     * because their columns are not null, this one takes it as the caller clearing the field. That
     * is what {@code assignedToId} already does, and what a nullable column with no default
     * allows: an issue can stop being ranked without stopping being an issue.
     *
     * <p>Only an explicit null clears it, which is what the published schema says. A blank string
     * is refused like any other value that names no member, so a client that sends {@code ""} is
     * told so rather than quietly losing the field.
     *
     * <p>The value comes out of the update map rather than a bound property, so it is taken as an
     * {@code Object}. Anything that is neither absent nor a known member is a client error (400),
     * not the class cast or {@code IllegalArgumentException} that would leave as a 500.
     *
     * @param value The raw map value: a priority name, or null to clear the priority.
     * @return The parsed priority, or null.
     * @throws InvalidRequestException if the value is not a known issue priority.
     */
    private static IssuePriority parseIssuePriority(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String name && !name.isBlank()) {
            try {
                return IssuePriority.valueOf(name);
            } catch (IllegalArgumentException e) {
                throw new InvalidRequestException("'" + name + "' is not a valid issue priority");
            }
        }
        throw new InvalidRequestException("'" + value + "' is not a valid issue priority");
    }

    /** Parses a required issue status; see {@link #parseIssueType}. */
    private static IssueStatus parseIssueStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new InvalidRequestException("Issue status is required");
        }
        try {
            return IssueStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("'" + status + "' is not a valid issue status");
        }
    }

    @Transactional(readOnly = true)
    public List<IssueDto> getAllIssuesByTaskId(Long taskId) {
        return issueRepository.findAllByTask_IdAndOrganization_Id(taskId,TenantContext.getCurrentOrgId()).stream()
                .map(issue -> issueMapper.toDto(issue))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<IssueDto> getAllIssuesByProjectId(Long projectId) {
        return issueRepository.findAllInProject(projectId, TenantContext.getCurrentOrgId()).stream()
                .map(issue -> issueMapper.toDto(issue))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public IssueDto getAnIssue(Long id) {
        IssueDto issueDto = issueRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .map(issue -> issueMapper.toDto(issue))
                .orElse(null);
        if (issueDto == null) {
            throw new ResourceNotFoundException("Issue with ID " + id + " was not found in this organization");
        }
        return issueDto;
    }

    @Transactional
    public IssueSimpleDto partialUpdateAnIssue(Map<String, Object> updates, Long id, List<MultipartFile> attachments, String entityType) {
        Issue issue = issueRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Issue with ID " + id + " was not found in this organization"));
        partialUpdateAnIssue(updates, issue);

        if (attachments != null) {
            for (MultipartFile att : attachments) {
                Attachment attachment = attachmentService.uploadAttachment(att, entityType, id, ISSUES_FOLDER);
                issue.addAttachment(attachment);
            }
        }
        return issueMapper.toSimpleDto(issueRepository.save(issue));
    }

    private void partialUpdateAnIssue(Map<String, Object> updates, Issue issue) {
        // The three links are judged together after the loop, so a patch that moves an issue
        // off its task and onto a project is not refused halfway through.
        boolean linksChanged = updates.containsKey("taskId") || updates.containsKey("projectId")
                || updates.containsKey("categoryId");
        Task task = issue.getTask();
        Project project = issue.getProject();
        Category category = issue.getCategory();
        if (updates.containsKey("taskId")) {
            task = resolveTask(readLinkId(updates.get("taskId"), "taskId"));
            if (task != null && !updates.containsKey("projectId")) {
                project = null; // taken from the new task; unlinking a task keeps the project
            }
        }
        if (updates.containsKey("projectId")) {
            project = resolveProject(readLinkId(updates.get("projectId"), "projectId"));
        }
        if (updates.containsKey("categoryId")) {
            category = resolveCategory(readLinkId(updates.get("categoryId"), "categoryId"));
        }
        if (linksChanged) {
            applyLinks(issue, task, project, category);
        }

        updates.forEach((key, value) -> {
            switch (key) {
                case "title":
                    issue.setTitle((String) value);
                    break;
                case "description":
                    issue.setDescription((String) value);
                    break;
                case "type":
                    issue.setType(parseIssueType((String) value));
                    break;
                case "status":
                    issue.setStatus(parseIssueStatus((String) value));
                    break;
                case "priority":
                    issue.setPriority(parseIssuePriority(value));
                    break;
                case "assignedToId":
                    issue.setAssignedTo(resolveAssignee(value));
                    break;
                case "taskId":
                case "projectId":
                case "categoryId":
                    break; // applied above, together
                default:
                    PartialUpdateKeys.reportUnknown(log, "issue", issue.getId(), key,
                            DELIBERATELY_DROPPED_UPDATE_KEYS);
                    break;
            }
        });
    }

    /**
     * Reads the {@code assignedToId} key of a partial issue update.
     *
     * <p>Null clears the assignment, which is what {@code assigned_to} being a nullable join
     * column already allows and what unassigning an issue means. Before this branch existed the
     * value went straight into {@code ((Number) value).longValue()} and a null answered 500, so
     * there was no way to unassign. See #645.
     *
     * @param value The raw map value: an employee id, or null to unassign.
     * @return The resolved employee, or null.
     * @throws InvalidRequestException if the value is neither null nor a number.
     * @throws ResourceNotFoundException if no such employee belongs to the caller's organization.
     */
    private Employee resolveAssignee(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new InvalidRequestException("assignedToId must be a number");
        }
        Long assigneeId = number.longValue();
        return employeeRepository.findByIdAndOrganizationId(assigneeId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Assignee (employee) with ID " + assigneeId + " was not found in this organization"));
    }


    @Transactional
    public void deleteAnIssue(Long id) {
        if(!issueRepository.existsByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())) {
            throw new ResourceNotFoundException("Issue with ID " + id + " was not found in this organization");
        }

        attachmentService.deleteAllAttachments("ISSUE", id);
        issueRepository.deleteByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId());
    }

}
