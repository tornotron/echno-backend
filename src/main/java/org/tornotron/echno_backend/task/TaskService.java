package org.tornotron.echno_backend.task;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.TaskDtoConvertor;
import org.tornotron.echno_backend.category.CategoryRepository;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.organization.Organization;
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

    private final TaskRepository taskRepository;
    private final EmployeeRepository employeeRepository;
    private final ProjectRepository projectRepository;
    private final CategoryRepository categoryRepository;


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
                       CategoryRepository categoryRepository
                      ) {
        this.taskRepository = taskRepository;
        this.employeeRepository = employeeRepository;
        this.projectRepository = projectRepository;
        this.categoryRepository = categoryRepository;
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
    public TaskSimpleDto addTask(TaskCreationDto taskCreationDto) {
        Task task = new Task();
        task.setTitle(taskCreationDto.getTitle());
        task.setStartDate(taskCreationDto.getStartDate());
        task.setEndDate(taskCreationDto.getEndDate());
        task.setProgress(taskCreationDto.getProgress());
        task.setStatus(TaskStatus.valueOf(taskCreationDto.getStatus()));

        if (taskCreationDto.getCreatorId() != null) {
            task.setCreator(employeeRepository.findById(taskCreationDto.getCreatorId())
                    .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + taskCreationDto.getCreatorId())));
        } else {
            throw new InvalidRequestException("Employee ID must be provided");
        }
        if (taskCreationDto.getProjectId() != null) {
            task.setProject(projectRepository.findById(taskCreationDto.getProjectId())
                    .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + taskCreationDto.getProjectId())));
        } else {
            throw new InvalidRequestException("Project ID must be provided");
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
                throw new InvalidRequestException("The following employee IDs which are being assigned are not part of "+organization.getOrganizationName()+" organization: " + nonExistentEmployeeIds);
            }

            Set<Employee> assignees = new HashSet<>(employeeRepository.findAllById(employeeIdsInTaskDto));
            task.setAssignees(assignees);

        }

        if(taskCreationDto.getCategoryId() != null) {
            task.setCategory(categoryRepository.findById(taskCreationDto.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + taskCreationDto.getCategoryId())));
        } else {
            throw new InvalidRequestException("Category must be provided");
        }

        if(taskCreationDto.getTags() != null && !taskCreationDto.getTags().isEmpty()) {
            List<String> cleanedTags = taskCreationDto.getTags().stream()
                    .filter(tag -> tag != null && !tag.trim().isEmpty())
                    .map(String::trim)
                    .distinct()
                    .toList();
            task.setTags(cleanedTags);
        }

        try {
            taskRepository.save(task);
        } catch (Exception e) {
            throw new DatabaseOperationException("Error while adding task: " + e.getMessage());
        }
        return TaskDtoConvertor.convertTaskToSimpleDto(task);
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
                .map(TaskDtoConvertor::convertTaskToDto);
    }

    /**
     * Retrieves a list of all tasks.
     *
     * @return A list of all task DTOs.
     */
    @Transactional(readOnly = true)
    public List<TaskDto> getAllTasks() {
        return taskRepository.findAll().stream()
                .map(TaskDtoConvertor::convertTaskToDto)
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
        TaskDto taskDto = taskRepository.findById(id)
                .map(TaskDtoConvertor::convertTaskToDto)
                .orElse(null);
        if(taskDto == null) {
            throw new ResourceNotFoundException("Task not found with id: " + id);
        } else {
            return taskDto;
        }
    }

    /**
     * Partially updates an existing task.
     *
     * @param updates A map of fields to update.
     * @param id      The ID of the task to update.
     * @throws ResourceNotFoundException if no task with the given ID is found.
     */
    @Transactional
    public TaskSimpleDto partialUpdateATask(Map<String,Object> updates,Long id) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found with id: " + id));

        updates.forEach((key,value) -> {
            switch (key) {
                case "title":
                    task.setTitle((String) value);
                    break;
                case "endDate":
                    task.setEndDate(value != null ? LocalDateTime.parse(value.toString()) : null);
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
            }
        });
       return TaskDtoConvertor.convertTaskToSimpleDto(taskRepository.save(task));
    }

    /**
     * Updates multiple tasks in a batch.
     *
     * @param updates A list of DTOs containing the updates for each task.
     */
    @Transactional
    public void batchUpdateTasks(List<TaskPatchDto> updates) {
        updates.forEach(update ->
                partialUpdateATask(update.getUpdates(), update.getId()));
    }

    /**
     * Deletes a task by its ID.
     *
     * @param id The ID of the task to delete.
     * @throws ResourceNotFoundException if no task with the given ID is found.
     */
    @Transactional
    public void deleteATask(Long id) {
        if(!taskRepository.existsById(id)) {
            throw new ResourceNotFoundException("Task not found with id: " + id);
        }
        taskRepository.deleteById(id);
    }

}