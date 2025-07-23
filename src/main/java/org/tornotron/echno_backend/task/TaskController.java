package org.tornotron.echno_backend.task;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.task.dto.TaskCreationDto;
import org.tornotron.echno_backend.task.dto.TaskDto;
import org.tornotron.echno_backend.task.dto.TaskPatchDto;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tasks")
@Validated
public class TaskController {

    private final TaskService service;
    private static final Logger logger = LoggerFactory.getLogger(TaskController.class);

    public TaskController(TaskService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse> createTask(@Valid @RequestBody TaskCreationDto taskCreationDto) {
        service.addTask(taskCreationDto);
        logger.info("Task Added Successfully");
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse("Task Created Successfully"));
    }

    @GetMapping
    public ResponseEntity<List<TaskDto>> readAllTasks(@RequestParam(defaultValue = "0") int pageNo,
                                                      @RequestParam(defaultValue = "10") int pageSize) {
        Page<TaskDto> tasks = service.getAllTasks(pageNo, pageSize);
        logger.info("All Tasks Retrieved Successfully");
        return new ResponseEntity<>(tasks.getContent(), HttpStatus.OK);
    }

    @GetMapping("{id}")
    public ResponseEntity<?> readATask(@PathVariable Long id) {
        TaskDto taskDto = service.getATask(id);
        return new ResponseEntity<>(taskDto, HttpStatus.OK);
    }

    @PatchMapping("{id}")
    public ResponseEntity<ApiResponse> partialUpdateATask(@RequestBody Map<String, Object> updates, @PathVariable Long id) {
        service.partialUpdateATask(updates, id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Task with id: " + id + " updated"));
    }

    @PatchMapping("/batch")
    public ResponseEntity<ApiResponse> batchUpdateTasks(@Valid @RequestBody List<TaskPatchDto> updates) {
        service.batchUpdateTasks(updates);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Batch update successful"));
    }

    @DeleteMapping("{id}")
    public ResponseEntity<ApiResponse> deleteATask(@PathVariable Long id) {
        service.deleteATask(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Task with id: " + id + " deleted"));
    }
}