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
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.task.dto.TaskCreationDto;
import org.tornotron.echno_backend.task.dto.TaskDto;
import org.tornotron.echno_backend.task.dto.TaskPatchDto;

import java.time.LocalDateTime;
import java.util.*;

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
    }

    @Transactional(readOnly = true)
    public Page<TaskDto> getAllTasks(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC, "id"));
        return taskRepository.findAll(pageable)
                .map(TaskDtoConvertor::convertTaskToDto);
    }

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

    @Transactional(readOnly = true)
    public void partialUpdateATask(Map<String,Object> updates,Long id) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found with id: " + id));

        updates.forEach((key,value) -> {
            switch (key) {
                case "title":
                    task.setTitle((String) value);
                    break;
                case "endDate":
                    task.setEndDate((LocalDateTime) value);
                    break;
                case "progress":
                    task.setProgress((Double) value);
                    break;
                case "status":
                    task.setStatus((String) value);
                    break;
            }
        });
    }

    public void batchUpdateTasks(List<TaskPatchDto> updates) {
        updates.forEach(update ->
                partialUpdateATask(update.getUpdates(), update.getId()));
    }

    public void deleteATask(Long id) {
        if(!taskRepository.existsById(id)) {
            throw new ResourceNotFoundException("Task not found with id: " + id);
        }
        taskRepository.deleteById(id);
    }

}
