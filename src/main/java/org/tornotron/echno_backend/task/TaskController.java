package org.tornotron.echno_backend.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springdoc.core.annotations.ParameterObject;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.common.payload.JsonPartBinder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(
        name = "Tasks",
        description = "Work items tracked against a project. A task carries a title, schedule, assignees, "
                + "category, tags, progress and status. Endpoints cover creating a task with attachments, "
                + "browsing and reading tasks, batch updates and deletion. Access is gated by the task "
                + "authorities, with an admin authority that grants all operations."
)
public class TaskController {

    private final TaskService service;
    private final JsonPartBinder jsonPartBinder;
    /** Logger for this class. */
    private static final Logger logger = LoggerFactory.getLogger(TaskController.class);

    /**
     * Constructs a TaskController with the given TaskService.
     *
     * @param service The service for handling task-related business logic.
     */
    public TaskController(TaskService service, JsonPartBinder jsonPartBinder) {
        this.service = service;
        this.jsonPartBinder = jsonPartBinder;
    }

    /**
     * Creates a new task.
     *
     * @param data JSON payload containing the details for the new task.
     * @return A {@link ResponseEntity} with a success message and HTTP status 201 (Created).
     */

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('task:create') or hasAuthority('task:admin')")
    @Operation(
            summary = "Create a task",
            description = "Creates a task from a multipart request. The data part carries the task details "
                    + "as JSON and the optional attachments part carries supporting files. Returns the "
                    + "created task as a simple view without its resolved assignees or category."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Task created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The data part is not valid task JSON, or a field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the task create or admin authority, or has no employee record in the current tenant, so the record would name nobody")
    })
    public ResponseEntity<TaskSimpleDto> createTask(
            @Parameter(schema = @Schema(implementation = TaskCreationDto.class))
            @RequestPart String data,
            @RequestParam(value = "attachments",required = false)List<MultipartFile> attachments) throws JsonProcessingException {
        TaskCreationDto dto = jsonPartBinder.read(data, TaskCreationDto.class);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addTask(dto,attachments));
    }


    /**
     * Retrieves a paginated list of all tasks.
     *
     * @param pageQuery Page index and page size, bounded by {@link PageQuery}.
     * @return A {@link ResponseEntity} containing the list of task DTOs and HTTP status 200 (OK).
     */
    @GetMapping
    @PreAuthorize("hasAuthority('task:read') or hasAuthority('task:admin')")
    @Operation(
            summary = "List tasks",
            description = "Returns a single page of tasks. The pageNo and pageSize parameters control "
                    + "paging; only the page content is returned, without paging metadata."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of tasks returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the task read or admin authority")
    })
    public ResponseEntity<List<TaskDto>> readAllTasks(@Valid @ParameterObject PageQuery pageQuery) {
        Page<TaskDto> tasks = service.getAllTasks(pageQuery.getPageNo(), pageQuery.getPageSize());
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
    @Operation(
            summary = "Get a task by id",
            description = "Returns a single task including its creator, assignees, category, issues and "
                    + "attachments."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Task found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the task read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No task with the given id")
    })
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
//    @PatchMapping("{id}")
//    @PreAuthorize("hasAuthority('task:update') or hasAuthority('task:admin')")
//    public ResponseEntity<TaskSimpleDto> partialUpdateATask(@RequestBody Map<String, Object> updates, @PathVariable Long id) {
//        return ResponseEntity.status(HttpStatus.OK).body(service.partialUpdateATask(updates, id));
//    }

    /**
     * Updates multiple tasks in a batch.
     *
     * @param updates A list of DTOs containing the updates for each task.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @PatchMapping("/batch")
    @PreAuthorize("hasAuthority('task:update') or hasAuthority('task:admin')")
    @Operation(
            summary = "Batch update tasks",
            description = "Applies partial updates to several tasks in one call. Each entry names a task "
                    + "id and the map of fields to change on that task."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Batch update applied"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "One of the update entries failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the task update or admin authority")
    })
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
    @Operation(
            summary = "Delete a task",
            description = "Deletes the task with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Task deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the task delete or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No task with the given id")
    })
    public ResponseEntity<ApiResponse> deleteATask(@PathVariable Long id) {
        service.deleteATask(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Task with id: " + id + " deleted"));
    }
}
