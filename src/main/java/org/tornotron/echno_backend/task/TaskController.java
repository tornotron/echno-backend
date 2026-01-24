package org.tornotron.echno_backend.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.task.dto.TaskCreationDto;
import org.tornotron.echno_backend.task.dto.TaskDto;
import org.tornotron.echno_backend.task.dto.TaskPatchDto;
import org.tornotron.echno_backend.task.dto.TaskSimpleDto;

import java.util.List;
import java.util.Map;

/**
 * REST controller for managing tasks.
 * Provides endpoints for creating, reading, updating, and deleting tasks.
 */
@RestController
@RequestMapping("/api/v1/tasks")
@Validated
public class TaskController {

    private final TaskService service;
    private final ObjectMapper objectMapper;
    /** Logger for this class. */
    private static final Logger logger = LoggerFactory.getLogger(TaskController.class);

    /**
     * Constructs a TaskController with the given TaskService.
     *
     * @param service The service for handling task-related business logic.
     */
    public TaskController(TaskService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a new task.
     *
     *  taskCreationDto DTO containing the details for the new task.
     * @return A {@link ResponseEntity} with a success message and HTTP status 201 (Created).
     */

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('task:create') or hasAuthority('task:admin')")
    public ResponseEntity<TaskSimpleDto> createTask(@RequestPart @Valid String data,
                                                    @RequestParam(value = "attachments",required = false)List<MultipartFile> attachments) throws JsonProcessingException {
        TaskCreationDto dto = objectMapper.readValue(data, TaskCreationDto.class);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addTask(dto,attachments));
    }


    /**
     * Retrieves a paginated list of all tasks.
     *
     * @param pageNo   The page number to retrieve (default is 0).
     * @param pageSize The number of tasks per page (default is 10).
     * @return A {@link ResponseEntity} containing the list of task DTOs and HTTP status 200 (OK).
     */
    @GetMapping
    @PreAuthorize("hasAuthority('task:read') or hasAuthority('task:admin')")
    public ResponseEntity<List<TaskDto>> readAllTasks(@RequestParam(defaultValue = "0") int pageNo,
                                                      @RequestParam(defaultValue = "10") int pageSize) {
        Page<TaskDto> tasks = service.getAllTasks(pageNo, pageSize);
        logger.info("All Tasks Retrieved Successfully");
        return new ResponseEntity<>(tasks.getContent(), HttpStatus.OK);
    }

    /**
     * Retrieves a single task by its ID.
     *
     * @param id The ID of the task to retrieve.
     * @return A {@link ResponseEntity} containing the task DTO and HTTP status 200 (OK).
     */
    @GetMapping("{id}")
    @PreAuthorize("hasAuthority('task:read') or hasAuthority('task:admin')")
    public ResponseEntity<?> readATask(@PathVariable Long id) {
        TaskDto taskDto = service.getATask(id);
        return new ResponseEntity<>(taskDto, HttpStatus.OK);
    }

    /**
     * Partially updates an existing task.
     *
     * @param updates A map of fields to update.
     * @param id      The ID of the task to update.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @PatchMapping("{id}")
    @PreAuthorize("hasAuthority('task:update') or hasAuthority('task:admin')")
    public ResponseEntity<TaskSimpleDto> partialUpdateATask(@RequestBody Map<String, Object> updates, @PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.OK).body(service.partialUpdateATask(updates, id));
    }

    /**
     * Updates multiple tasks in a batch.
     *
     * @param updates A list of DTOs containing the updates for each task.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @PatchMapping("/batch")
    @PreAuthorize("hasAuthority('task:update') or hasAuthority('task:admin')")
    public ResponseEntity<ApiResponse> batchUpdateTasks(@Valid @RequestBody List<TaskPatchDto> updates) {
        service.batchUpdateTasks(updates);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Batch update successful"));
    }

    /**
     * Deletes a task by its ID.
     *
     * @param id The ID of the task to delete.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @DeleteMapping("{id}")
    @PreAuthorize("hasAuthority('task:delete') or hasAuthority('task:admin')")
    public ResponseEntity<ApiResponse> deleteATask(@PathVariable Long id) {
        service.deleteATask(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Task with id: " + id + " deleted"));
    }
}
