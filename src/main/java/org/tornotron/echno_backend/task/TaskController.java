package org.tornotron.echno_backend.task;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.task.dto.TaskDto;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tasks")
@Validated
public class TaskController {

    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    @PostMapping(consumes = {MediaType.MULTIPART_FORM_DATA_VALUE} )
    public ResponseEntity<ApiResponse> createTask(
                                             @RequestPart("photo") MultipartFile photo,
                                             @RequestPart("task") String taskDtoString
    ) {
        service.addTask(taskDtoString,photo);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse("Task Created Successfully"));
    }

    @GetMapping
    public ResponseEntity<List<TaskDto>> readAllTasks() {
        return new ResponseEntity<>(service.getAllTasks(), HttpStatus.OK);
    }

    @GetMapping("{id}")
    public ResponseEntity<?> readATask(@PathVariable Long id) {
        TaskDto task = service.getATask(id);
        return new ResponseEntity<>(task, HttpStatus.OK);
    }

    @PutMapping("{id}")
    public ResponseEntity<ApiResponse> updateTask(@Valid @RequestBody Task updatedTask, @PathVariable Long id) {
        boolean updated = service.updateATask(updatedTask, id);
        if (updated) {
            return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Task with id: " + id + " has been updated"));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiResponse("Task with id: " + id + " not found"));
    }

    @DeleteMapping("{id}")
    public ResponseEntity<ApiResponse> deleteTask(@PathVariable Long id) {
        service.deleteATask(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Task with id: " + id + " has been deleted"));
    }
}