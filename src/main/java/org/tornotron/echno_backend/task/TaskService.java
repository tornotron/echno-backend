package org.tornotron.echno_backend.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.category.Category;
import org.tornotron.echno_backend.category.CategoryRepository;
import org.tornotron.echno_backend.category.CategoryService;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.EmployeeService;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.project.ProjectService;
import org.tornotron.echno_backend.task.dto.TaskCreationDto;
import org.tornotron.echno_backend.task.dto.TaskDto;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class TaskService {

    private final TaskRepository taskRepository;
    private final EmployeeRepository employeeRepository;
    private final ProjectRepository projectRepository;
    private final CategoryRepository categoryRepository;


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


    private TaskDto convertToDto(Task task) {
        TaskDto dto = new TaskDto();
        dto.setId(task.getId());
        dto.setTitle(task.getTitle());
        dto.setStartDate(task.getStartDate());
        dto.setEndDate(task.getEndDate());
        dto.setCreatorId(task.getCreator().getId());
        dto.setProjectId(task.getProject().getId());
        dto.setAssigneeIds(task.getAssignees().stream()
                .map(Employee::getId)
                .collect(Collectors.toSet()));
        dto.setCategoryId(task.getCategory().getId());
        dto.setProgress(task.getProgress());
        dto.setTags(task.getTags());
        dto.setCreatedAt(task.getCreatedAt());
        dto.setUpdatedAt(task.getUpdatedAt());
        dto.setStatus(task.getStatus());
        return dto;
    }

    public void addTask(TaskCreationDto taskCreationDto) {
        Task task = new Task();
        task.setTitle(taskCreationDto.getTitle());
        task.setStartDate(taskCreationDto.getStartDate());
        task.setEndDate(taskCreationDto.getEndDate());
        task.setProgress(taskCreationDto.getProgress());
        task.setStatus(taskCreationDto.getStatus());

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
            Set<Employee> assignees = new HashSet<>();
            for(Long assigneeId : taskCreationDto.getAssigneeIds()) {
                Employee assignee = employeeRepository.findById(assigneeId).
                        orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + assigneeId));
                assignees.add(assignee);
            }
            task.setAssignees(assignees);

        }

        if(taskCreationDto.getCategory() != null) {
            task.setCategory(categoryRepository.findCategoryByName(taskCreationDto.getCategory())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found with name: " + taskCreationDto.getCategory())));
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
    }

    @Transactional(readOnly = true)
    public Page<TaskDto> getAllTasks(int pageNo, int pageSize) {
        Pageable pageable = Pageable.ofSize(pageSize).withPage(pageNo);
        return taskRepository.findAll(pageable)
                .map(this::convertToDto);
    }

}
