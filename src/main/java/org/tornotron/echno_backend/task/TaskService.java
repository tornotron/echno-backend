package org.tornotron.echno_backend.task;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.DtoConversions.TaskDtoConvertor;
import org.tornotron.echno_backend.category.CategoryRepository;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.DtoConversions.ProjectDtoConvertor;
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
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service class for managing tasks.
 * Handles business logic related to task creation, retrieval, updates, and deletion.
 */
@Service
public class TaskService {

    private static final String TASKS_FOLDER = "tasks";

    private final TaskRepository taskRepository;
    private final EmployeeRepository employeeRepository;
    private final ProjectRepository projectRepository;
    private final CategoryRepository categoryRepository;
    private final AttachmentService attachmentService;
    private final FileStorageService fileStorageService;


    /**
     * Constructs a TaskService with the necessary repositories.
     *
     * @param taskRepository     The repository for task data access.
     * @param employeeRepository The repository for employee data access.
     * @param projectRepository  The repository for project data access.
     * @param categoryRepository The repository for category data access.
     */
    public TaskService(TaskRepository taskRepository,
                       EmployeeRepository employeeRepository,
                       ProjectRepository projectRepository,
                       CategoryRepository categoryRepository,
                       AttachmentService attachmentService,
                       FileStorageService fileStorageService) {
        this.taskRepository = taskRepository;
        this.employeeRepository = employeeRepository;
        this.projectRepository = projectRepository;
        this.categoryRepository = categoryRepository;
        this.attachmentService = attachmentService;
        this.fileStorageService = fileStorageService;
    }


    /**
     * Creates a new task.
     *
     * @param taskCreationDto DTO containing the details for the new task.
     * @throws ResourceNotFoundException if the creator, project, or category is not found.
     * @throws InvalidRequestException if required fields like creatorId, projectId, or categoryId are missing,
     *                                 or if assigned employees do not belong to the project's organization.
     * @throws DatabaseOperationException if there is an error saving the task.
     */
    @Transactional
    public TaskSimpleDto addTask(TaskCreationDto taskCreationDto, List<MultipartFile> attachments) {
        Task task = new Task();
        task.setTitle(taskCreationDto.getTitle());
        task.setStartDate(taskCreationDto.getStartDate());
        task.setDescription(taskCreationDto.getDescription());
        task.setEndDate(taskCreationDto.getEndDate());
        task.setProgress(taskCreationDto.getProgress());
        task.setStatus(TaskStatus.valueOf(taskCreationDto.getStatus()));

        if (taskCreationDto.getCreatorId() != null) {
            task.setCreator(employeeRepository.findByIdAndOrganizationId(taskCreationDto.getCreatorId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Creator (employee) with ID " + taskCreationDto.getCreatorId() + " was not found in this organization")));
        } else {
            throw new InvalidRequestException("A creatorId is required to create a task");
        }
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

        return TaskDtoConvertor.convertTaskToSimpleDto(savedTask);
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
                .map(task -> TaskDtoConvertor.convertTaskToDto(task,fileStorageService));
    }

    /**
     * Retrieves a list of all tasks.
     *
     * @return A list of all task DTOs.
     */
    @Transactional(readOnly = true)
    public List<TaskDto> getAllTasks() {
        return taskRepository.findAll().stream()
                .map(task -> TaskDtoConvertor.convertTaskToDto(task,fileStorageService))
                .toList();
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
                .map(task -> TaskDtoConvertor.convertTaskToDto(task,fileStorageService))
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
                .map(task -> TaskDtoConvertor.convertTaskToDto(task,fileStorageService))
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
       return TaskDtoConvertor.convertTaskToSimpleDto(savedTask);
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
                case "endDate":
                    task.setEndDate(LocalDateTime.parse((String) value, DateTimeFormatter.ISO_DATE_TIME));
                    break;
                case "progress":
                    if (value == null) {
                        task.setProgress(null);
                    } else if (value instanceof Number) {
                        task.setProgress(((Number) value).doubleValue());
                    }
                    break;
                case "status":
                    task.setStatus(TaskStatus.valueOf((String) value));
                    break;
                case "tags":
                    updateTags(value, task);
                    break;
            }
        });
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
        Double progress = ProjectDtoConvertor.calculateProjectProgress(tasks);
        project.setProgress(progress);
        projectRepository.save(project);
    }

}
