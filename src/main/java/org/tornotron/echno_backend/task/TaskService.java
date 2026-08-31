package org.tornotron.echno_backend.task;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tornotron.echno_backend.category.Category;
import org.tornotron.echno_backend.common.payload.PartialUpdateKeys;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.task.mapper.TaskMapper;
import org.tornotron.echno_backend.category.CategoryRepository;
import org.tornotron.echno_backend.common.conversions.DateConversion;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.project.ProjectProgressCalculator;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.task.dto.TaskCreationDto;
import org.tornotron.echno_backend.task.dto.TaskDto;
import org.tornotron.echno_backend.task.dto.TaskPatchDto;
import org.tornotron.echno_backend.task.dto.TaskSimpleDto;
import org.tornotron.echno_backend.task.enums.TaskStatus;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service class for managing tasks.
 * Handles business logic related to task creation, retrieval, updates, and deletion.
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private static final String TASKS_FOLDER = "tasks";

    /**
     * Keys the web client puts in a task update that this endpoint has no field for, and drops on
     * purpose rather than noisily. See {@link PartialUpdateKeys} for why the rest are warned about
     * rather than refused.
     *
     * <p>{@code attachments} is the only one: the client sets {@code attachments: []} on every
     * update so the backend can tell "no upload" from "untouched", and the files themselves travel
     * as their own multipart part, so the key in the JSON part carries nothing to apply.
     *
     * <p>{@code creatorId}, {@code priority} and {@code projectId} are deliberately not here.
     * They arrive only when a caller sets them, and each is on the list in echno-core#57 as
     * something the client should stop sending: {@code creatorId} carries the editing user, so
     * honouring it would rewrite a task's creator to whoever last touched it; {@code priority} has
     * no column anywhere; and there is no "move task" screen behind {@code projectId}. A warning
     * for each is the point.
     */
    private static final Set<String> DELIBERATELY_DROPPED_UPDATE_KEYS = Set.of("attachments");

    private final TaskRepository taskRepository;
    private final EmployeeRepository employeeRepository;
    private final ProjectRepository projectRepository;
    private final CategoryRepository categoryRepository;
    private final AttachmentService attachmentService;
    private final CurrentEmployeeService currentEmployeeService;
    private final TaskMapper taskMapper;
    private final PayloadValidator payloadValidator;

    /**
     * Constructs a TaskService with the necessary repositories.
     *
     * @param taskRepository     The repository for task data access.
     * @param employeeRepository The repository for employee data access.
     * @param projectRepository  The repository for project data access.
     * @param categoryRepository The repository for category data access.
     * @param attachmentService  The service for attachment operations.
     * @param currentEmployeeService Resolves the caller to the employee a task is recorded as
     *                           having been created by.
     * @param taskMapper         The mapper between tasks and their DTOs.
     * @param payloadValidator   Runs the create payload's own constraints.
     */
    public TaskService(TaskRepository taskRepository,
                       EmployeeRepository employeeRepository,
                       ProjectRepository projectRepository,
                       CategoryRepository categoryRepository,
                       AttachmentService attachmentService,
                       CurrentEmployeeService currentEmployeeService,
                       TaskMapper taskMapper,
                       PayloadValidator payloadValidator) {
        this.taskRepository = taskRepository;
        this.employeeRepository = employeeRepository;
        this.projectRepository = projectRepository;
        this.categoryRepository = categoryRepository;
        this.attachmentService = attachmentService;
        this.currentEmployeeService = currentEmployeeService;
        this.taskMapper = taskMapper;
        this.payloadValidator = payloadValidator;
    }

    /**
     * Creates a new task.
     *
     * @param taskCreationDto DTO containing the details for the new task.
     * <p>The creator is the signed-in caller, stamped here rather than read off the payload. The
     * endpoint is role-gated, so the forgery this closes needed a role that may manage tasks
     * already; what it bought was a task recorded as somebody else's, which no client has ever
     * asked for. See {@link org.tornotron.echno_backend.common.service.CurrentEmployeeService}.
     *
     * @throws ConstraintViolationException if the payload fails its own constraints.
     * @throws org.springframework.security.access.AccessDeniedException if the caller has no
     *                                 employee record in this organization.
     * @throws ResourceNotFoundException if the project or category is not found.
     * @throws InvalidRequestException if required fields like projectId or categoryId are missing,
     *                                 or if assigned employees do not belong to the project's organization.
     * @throws DatabaseOperationException if there is an error saving the task.
     */
    @Transactional
    public TaskSimpleDto addTask(TaskCreationDto taskCreationDto, List<MultipartFile> attachments) {
        payloadValidator.requireValid(taskCreationDto);
        Task task = new Task();
        task.setTitle(taskCreationDto.getTitle());
        task.setStartDate(taskCreationDto.getStartDate());
        task.setDescription(taskCreationDto.getDescription());
        task.setEndDate(taskCreationDto.getEndDate());
        task.setProgress(taskCreationDto.getProgress());
        task.setStatus(TaskStatus.valueOf(taskCreationDto.getStatus()));

        task.setCreator(currentEmployeeService.requireCurrentEmployee("create a task"));
        if (taskCreationDto.getProjectId() != null) {
            var project = projectRepository.findByIdAndOrganization_Id(taskCreationDto.getProjectId(),TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Project with ID " + taskCreationDto.getProjectId() + " was not found in this organization"));
            task.setProject(project);
            task.setOrganization(project.getOrganization());
        } else {
            throw new InvalidRequestException("A projectId is required to create a task");
        }

        if(taskCreationDto.getAssigneeIds() != null && !taskCreationDto.getAssigneeIds().isEmpty()) {
            Set<Long> employeeIdsInTaskDto = new HashSet<>(taskCreationDto.getAssigneeIds());
            Organization organization = task.getProject().getOrganization();
            List<Employee> employeesInOrganization = employeeRepository.findEmployeesByOrganization_Id(organization.getId());

            Set<Long> validEmployeeIdsInOrg = employeesInOrganization.stream()
                    .map(Employee::getId)
                    .collect(Collectors.toSet());

            Set<Long> nonExistentEmployeeIds = new HashSet<>(employeeIdsInTaskDto);
            nonExistentEmployeeIds.removeAll(validEmployeeIdsInOrg);

            if (!nonExistentEmployeeIds.isEmpty()) {
                throw new InvalidRequestException("Employee IDs " + nonExistentEmployeeIds + " are not members of organization '" + organization.getOrganizationName() + "' and cannot be assigned to this task");
            }

            Set<Employee> assignees = new HashSet<>(employeeRepository.findAllById(employeeIdsInTaskDto));
            task.setAssignees(assignees);

        }

        if(taskCreationDto.getCategoryId() != null) {
            task.setCategory(categoryRepository.findByIdAndOrganization_Id(taskCreationDto.getCategoryId(),TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category with ID " + taskCreationDto.getCategoryId() + " was not found in this organization")));
        } else {
            throw new InvalidRequestException("A categoryId is required to create a task");
        }

        if(taskCreationDto.getTags() != null && !taskCreationDto.getTags().isEmpty()) {
            List<String> cleanedTags = taskCreationDto.getTags().stream()
                    .filter(tag -> tag != null && !tag.trim().isEmpty())
                    .map(String::trim)
                    .distinct()
                    .toList();
            task.setTags(cleanedTags);
        }

        Task savedTask = taskRepository.save(task);

        if(attachments != null && !attachments.isEmpty()) {
            List<Attachment> savedAttachments = attachmentService.uploadAttachments(attachments,"TASK", savedTask.getId(), TASKS_FOLDER);
            for(Attachment attachment : savedAttachments) {
                savedTask.addAttachment(attachment);
            }
            savedTask = taskRepository.save(savedTask);
        }

        updateProjectProgress(savedTask.getProject());

        return taskMapper.toSimpleDto(savedTask);
    }

    /**
     * Retrieves a paginated list of all tasks.
     *
     * @param pageNo   The page number to retrieve.
     * @param pageSize The number of tasks per page.
     * @return A {@link Page} of task DTOs.
     */
    @Transactional(readOnly = true)
    public Page<TaskDto> getAllTasks(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC, "id"));
        return taskRepository.findAll(pageable)
                .map(task -> taskMapper.toDto(task));
    }

    /**
     * Retrieves a page of tasks under optional project and free-text filters.
     *
     * <p>Unlike {@link #getAllTasks(int, int)} the caller keeps the {@link Page}, so the total row
     * count and the page index survive to the response and a truncated result says so. Newest
     * first, because a task list is read from the recent end.
     *
     * @param pageNo    Zero-based page index; a negative value is treated as zero.
     * @param pageSize  Rows per page, clamped to {@link UnpagedResultCap#MAX_ROWS} so one request
     *                  cannot re-create the unbounded read this endpoint exists to replace.
     * @param projectId Optional project filter; null means every project.
     * @param search    Optional case-insensitive match on title or description; blank means none.
     * @return A {@link Page} of task DTOs.
     */
    @Transactional(readOnly = true)
    public Page<TaskDto> getTasksPaginated(int pageNo, int pageSize, Long projectId, String search) {
        int page = Math.max(pageNo, 0);
        int size = Math.clamp(pageSize, 1, UnpagedResultCap.MAX_ROWS);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        return taskRepository.search(projectId, searchPattern(search), pageable)
                .map(task -> taskMapper.toDto(task));
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
     * Counts the current tenant's tasks by status.
     *
     * <p>Replaces a full-table read that grouped in memory. The grouping happens in the database,
     * so the work is proportional to the number of distinct statuses rather than to the number of
     * tasks.
     *
     * @return Status name to task count, with a null status reported as {@code Unknown}.
     */
    @Transactional(readOnly = true)
    public Map<String, Long> countTasksByStatus() {
        return taskRepository.countByStatus().stream()
                .collect(Collectors.toMap(
                        row -> row.getStatus() == null ? "Unknown" : row.getStatus().toString(),
                        TaskRepository.TaskStatusCount::getTotal));
    }

    /**
     * Retrieves a single task by its ID.
     *
     * @param id The ID of the task to retrieve.
     * @return The task DTO.
     * @throws ResourceNotFoundException if no task with the given ID is found.
     */
    @Transactional(readOnly = true)
    public TaskDto getATask(Long id) {
        TaskDto taskDto = taskRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .map(task -> taskMapper.toDto(task))
                .orElse(null);
        if(taskDto == null) {
            throw new ResourceNotFoundException("Task with ID " + id + " was not found in this organization");
        } else {
            return taskDto;
        }
    }

    @Transactional(readOnly = true)
    public List<TaskDto> getTasksByProjectId(Long projectId) {
        return taskRepository.findAllByProject_IdAndOrganization_Id(projectId,TenantContext.getCurrentOrgId()).stream()
                .map(task -> taskMapper.toDto(task))
                .collect(Collectors.toList());
    }

    /**
     * Partially updates an existing task.
     *
     * @param updates A map of fields to update.
     * @param id      The ID of the task to update.
     * @throws ResourceNotFoundException if no task with the given ID is found.
     */
    @Transactional
    public TaskSimpleDto partialUpdateATask(Map<String,Object> updates,Long id,List<MultipartFile> attachments, String entityType) {
        Task task = taskRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Task with ID " + id + " was not found in this organization"));
        partialUpdateATask(updates, task);

        if (attachments != null) {
            for (MultipartFile att:attachments) {
                Attachment attachment = attachmentService.uploadAttachment(att,entityType,id,TASKS_FOLDER);
                task.addAttachment(attachment);
            }
        }

       Task savedTask = taskRepository.save(task);
       updateProjectProgress(savedTask.getProject());
       return taskMapper.toSimpleDto(savedTask);
    }

    private void partialUpdateATask(Map<String, Object> updates, Task task) {
        updates.forEach((key,value) -> {
            switch (key) {
                case "title":
                    task.setTitle((String) value);
                    break;
                case "description":
                    task.setDescription((String) value);
                    break;
                case "startDate":
                    task.setStartDate(DateConversion.parseLocalDateTime(value));
                    break;
                case "endDate":
                    task.setEndDate(DateConversion.parseLocalDateTime(value));
                    break;
                case "progress":
                    if (value == null) {
                        task.setProgress(null);
                    } else if (value instanceof Number) {
                        task.setProgress(((Number) value).doubleValue());
                    }
                    break;
                case "status":
                    task.setStatus(requireStatus(value));
                    break;
                case "tags":
                    updateTags(value, task);
                    break;
                case "assigneeIds":
                    task.setAssignees(resolveAssignees(value));
                    break;
                case "categoryId":
                    task.setCategory(resolveCategory(value));
                    break;
                default:
                    PartialUpdateKeys.reportUnknown(log, "task", task.getId(), key,
                            DELIBERATELY_DROPPED_UPDATE_KEYS);
                    break;
            }
        });
    }

    /**
     * Reads the {@code status} key of a partial task update.
     *
     * <p>Null is refused rather than applied. {@code Task.status} is a {@code NOT NULL} column, so
     * clearing it cannot be written; before this refusal existed the branch reached
     * {@code TaskStatus.valueOf(null)} and answered 500. See #645.
     *
     * @param value The raw map value.
     * @return The parsed status.
     * @throws InvalidRequestException if the value is null.
     */
    private TaskStatus requireStatus(Object value) {
        if (value == null) {
            throw new InvalidRequestException("A task must have a status; status cannot be cleared");
        }
        return TaskStatus.valueOf((String) value);
    }


    /**
     * Resolves the replacement assignee set for a task update.
     *
     * <p>The list is the whole set, not an addition: the client sends every assignee the task
     * should end up with, so a shorter list unassigns and an empty one or a null clears the task.
     * That is the shape the edit form already submits.
     *
     * <p>Everyone named has to be an employee of the caller's organization, which is checked in one
     * query rather than by trusting the ids. Reassignment used to be dropped here in silence, so an
     * id belonging to another tenant reaching a task is the failure worth being loud about.
     *
     * @param value The raw {@code assigneeIds} value from the update map.
     * @return The employees to assign, empty when the caller sent nothing to assign.
     * @throws InvalidRequestException if the value is not a list of ids, or names anyone who is not
     *                                 an employee of this organization.
     */
    private Set<Employee> resolveAssignees(Object value) {
        if (value == null) {
            return new HashSet<>();
        }
        if (!(value instanceof List<?> rawIds)) {
            throw new InvalidRequestException("assigneeIds must be provided as a list of employee ids");
        }
        if (rawIds.isEmpty()) {
            return new HashSet<>();
        }

        Set<Long> requested = new LinkedHashSet<>();
        for (Object rawId : rawIds) {
            if (!(rawId instanceof Number number)) {
                throw new InvalidRequestException("Each assignee id must be a number");
            }
            requested.add(number.longValue());
        }

        List<Employee> found = employeeRepository.findAllByIdInAndOrganizationId(
                requested, TenantContext.getCurrentOrgId());
        if (found.size() != requested.size()) {
            Set<Long> missing = new LinkedHashSet<>(requested);
            found.forEach(employee -> missing.remove(employee.getId()));
            throw new InvalidRequestException("Employee IDs " + missing
                    + " are not members of this organization and cannot be assigned to this task");
        }
        return new HashSet<>(found);
    }

    /**
     * Resolves the category a task update moves the task to.
     *
     * <p>Clearing is refused rather than allowed: {@code addTask} insists on a category, so a task
     * with none is a state creation cannot produce and nothing downstream expects.
     *
     * @param value The raw {@code categoryId} value from the update map.
     * @return The category to set.
     * @throws InvalidRequestException if the value is null or not a number.
     * @throws ResourceNotFoundException if no such category exists in this organization.
     */
    private Category resolveCategory(Object value) {
        if (value == null) {
            throw new InvalidRequestException("A task must have a category; categoryId cannot be cleared");
        }
        if (!(value instanceof Number number)) {
            throw new InvalidRequestException("categoryId must be a number");
        }
        Long categoryId = number.longValue();
        return categoryRepository.findByIdAndOrganization_Id(categoryId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Category with ID " + categoryId + " was not found in this organization"));
    }

    private void updateTags(Object rawTags, Task task) {
        if (rawTags == null) {
            task.setTags(null);
            return;
        }
        if (!(rawTags instanceof List<?>)) {
            throw new InvalidRequestException("Tags must be provided as a list of strings");
        }

        List<?> tagValues = (List<?>) rawTags;
        List<String> cleanedTags = tagValues.stream()
                .map(tag -> {
                    if (tag == null) {
                        return null;
                    }
                    if (!(tag instanceof String)) {
                        throw new InvalidRequestException("Each tag must be a string value");
                    }
                    String trimmedTag = ((String) tag).trim();
                    return trimmedTag.isEmpty() ? null : trimmedTag;
                })
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        task.setTags(cleanedTags);
    }

    /**
     * Updates multiple tasks in a batch.
     *
     * @param updates A list of DTOs containing the updates for each task.
     */
    @Transactional
    public void batchUpdateTasks(List<TaskPatchDto> updates) {
        List<Long> taskIds = updates.stream().map(TaskPatchDto::getId).collect(Collectors.toList());
        List<Task> tasks = taskRepository.findAllById(taskIds);

        Map<Long, Task> taskMap = tasks.stream().collect(Collectors.toMap(Task::getId, task -> task));

        updates.forEach(update -> {
            Task task = taskMap.get(update.getId());
            if (task != null) {
                partialUpdateATask(update.getUpdates(), task);
            }
        });

        taskRepository.saveAll(tasks);

        tasks.stream()
                .map(Task::getProject)
                .distinct()
                .forEach(this::updateProjectProgress);
    }

    /**
     * Deletes a task by its ID.
     *
     * @param id The ID of the task to delete.
     * @throws ResourceNotFoundException if no task with the given ID is found.
     */
    @Transactional
    public void deleteATask(Long id) {
        Task task = taskRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Task with ID " + id + " was not found in this organization"));
        Project project = task.getProject();
        taskRepository.deleteByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId());
        taskRepository.flush();
        updateProjectProgress(project);
    }

    private void updateProjectProgress(Project project) {
        List<Task> tasks = taskRepository.findByProject_Id(project.getId());
        Double progress = ProjectProgressCalculator.calculate(tasks);
        project.setProgress(progress);
        projectRepository.save(project);
    }

}
