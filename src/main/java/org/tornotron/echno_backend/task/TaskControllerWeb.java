package org.tornotron.echno_backend.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.task.dto.TaskCreationDto;
import org.tornotron.echno_backend.task.dto.TaskDto;
import org.tornotron.echno_backend.task.dto.TaskPatchDto;
import org.tornotron.echno_backend.task.dto.TaskSimpleDto;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tasks/web")
@Validated
@Tag(
        name = "Tasks",
        description = "Web-client twin of the task endpoints. Adds listing tasks by project, alongside "
                + "the same create, read, batch update and delete operations as the base task API. "
                + "Access is gated by tenant membership, with mutations restricted to a system admin "
                + "or project manager."
)
public class TaskControllerWeb {

    private final TaskService service;
    private final ObjectMapper objectMapper;
    /** Logger for this class. */
    private static final Logger logger = LoggerFactory.getLogger(TaskController.class);

    /**
     * Constructs a TaskController with the given TaskService.
     *
     * @param service The service for handling task-related business logic.
     */
    public TaskControllerWeb(TaskService service,ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.service = service;
    }

    /**
     * Creates a new task.
     *
     *  taskCreationDto DTO containing the details for the new task.
     * @return A {@link ResponseEntity} with a success message and HTTP status 201 (Created).
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Create a task",
            description = "Creates a task from a multipart request. The data part carries the task "
                    + "details as JSON and the optional attachments part carries supporting files. "
                    + "Returns the created task as a simple view."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Task created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The data part is not valid task JSON, or a field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
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
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "List tasks",
            description = "Returns a single page of tasks. The pageNo and pageSize parameters control "
                    + "paging; only the page content is returned, without paging metadata."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of tasks returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<TaskDto>> readAllTasks(@RequestParam(defaultValue = "0") int pageNo,
                                                      @RequestParam(defaultValue = "10") int pageSize) {
        Page<TaskDto> tasks = service.getAllTasks(pageNo, pageSize);
        logger.info("All Tasks Retrieved Successfully");
        return new ResponseEntity<>(tasks.getContent(), HttpStatus.OK);
    }

    @GetMapping("/projectId/{projectId}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "List tasks for a project",
            description = "Returns every task belonging to the given project."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Tasks returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No project with the given id")
    })
    public ResponseEntity<List<TaskDto>> readAllTasksForProject(@PathVariable Long projectId) {
        return ResponseEntity.status(HttpStatus.OK).body(service.getTasksByProjectId(projectId));
    }

    /**
     * Retrieves a single task by its ID.
     *
     * @param id The ID of the task to retrieve.
     * @return A {@link ResponseEntity} containing the task DTO and HTTP status 200 (OK).
     */
    @GetMapping("{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Get a task by id",
            description = "Returns a single task including its creator, assignees, category, issues and "
                    + "attachments."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Task found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No task with the given id")
    })
    public ResponseEntity<?> readATask(@PathVariable Long id) {
        TaskDto taskDto = service.getATask(id);
        return new ResponseEntity<>(taskDto, HttpStatus.OK);
    }

    /**
     * Partially updates an existing task.
     *
     * @param id      The ID of the task to update.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @PatchMapping(value = "{id}",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Partially update a task",
            description = "Applies field updates from a multipart request. The data part carries the "
                    + "changed fields as JSON and the optional attachments part adds files under the "
                    + "given entityType."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Task updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The data part is not valid JSON, or a field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No task with the given id")
    })
    public ResponseEntity<TaskSimpleDto> partialUpdateATask(@RequestPart(value = "data", required = false) String data,
                                                            @PathVariable Long id,
                                                            @RequestParam(value = "attachments", required = false) List<MultipartFile> attachments,
                                                            @RequestParam(value = "entityType", required = false, defaultValue = "TASK_ATTACHMENTS") String entityType) throws JsonProcessingException
    {
        Map<String, Object> updates = data != null
                ? objectMapper.readValue(data, new TypeReference<>() {}) : Map.of();
        return ResponseEntity.status(HttpStatus.OK).body(service.partialUpdateATask(updates, id,attachments, entityType));
    }

    /**
     * Updates multiple tasks in a batch.
     *
     * @param updates A list of DTOs containing the updates for each task.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @PatchMapping("/batch")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Batch update tasks",
            description = "Applies partial updates to several tasks in one call. Each entry names a "
                    + "task id and the map of fields to change on that task."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Batch update applied"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "One of the update entries failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
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
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Delete a task",
            description = "Deletes the task with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Task deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No task with the given id")
    })
    public ResponseEntity<ApiResponse> deleteATask(@PathVariable Long id) {
        service.deleteATask(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Task with id: " + id + " deleted"));
    }
}
