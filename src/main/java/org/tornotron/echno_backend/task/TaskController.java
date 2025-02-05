package org.tornotron.echno_backend.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.task.dto.TaskCreationDto;
import org.tornotron.echno_backend.task.dto.TaskDto;

import java.util.List;

@RestController
@RequestMapping("/tasks")
public class TaskController {

    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    @PostMapping(consumes = {MediaType.MULTIPART_FORM_DATA_VALUE} )
    public ResponseEntity<String> createTask(
                                             @RequestPart("photo") MultipartFile photo,
                                             @RequestPart("task") String taskDtoString
    ) {
        service.addTask(taskDtoString,photo);
        return ResponseEntity.status(HttpStatus.CREATED).body("Task created successfully");
    }

    @GetMapping
    public ResponseEntity<List<TaskDto>> readAllTasks() {
        return new ResponseEntity<>(service.getAllTasks(), HttpStatus.OK);
    }

    @GetMapping("{id}")
    public ResponseEntity<?> readATask(@PathVariable Long id) {
        TaskDto task = service.getATask(id);
        if (task != null) {
            return new ResponseEntity<>(task, HttpStatus.OK);
        }
        return new ResponseEntity<>("Task with id: " + id + " does not exist", HttpStatus.NOT_FOUND);
    }

    @PutMapping("{id}")
    public ResponseEntity<String> updateTask(@RequestBody Task updatedTask, @PathVariable Long id) {
        boolean updated = service.updateATask(updatedTask, id);
        if (updated) {
            return new ResponseEntity<>("Task with id: " + id + " has been updated", HttpStatus.OK);
        }
        return new ResponseEntity<>("Task with id: " + id + " not found", HttpStatus.NOT_FOUND);
    }

    @DeleteMapping("{id}")
    public ResponseEntity<String> deleteTask(@PathVariable Long id) {
        service.deleteATask(id);
        return new ResponseEntity<>("Task with id: " + id + " deleted", HttpStatus.OK);
    }
}