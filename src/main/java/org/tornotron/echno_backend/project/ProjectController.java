package org.tornotron.echno_backend.project;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.project.dto.ProjectDto;

import java.util.List;

@RestController
@RequestMapping("/projects")
@Validated
public class ProjectController {

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<String> createProject(@Valid @RequestBody Project project) {
        Boolean created = service.addProject(project);
        if(created) {
            return ResponseEntity.status(HttpStatus.CREATED).body("Project Added Successfully");
        }
        throw new DatabaseOperationException("Project could not be created");
    }

    @GetMapping
    public ResponseEntity<List<ProjectDto>> readAllProjects() {
        return new ResponseEntity<>(service.getAllProjects(),HttpStatus.OK);
    }

    @GetMapping("{id}")
    public ResponseEntity<?> readAProject(@PathVariable Long id) {
        ProjectDto project = service.getAProject(id);
        if(project != null) {
            return new ResponseEntity<>(project,HttpStatus.OK);
        }
        throw new ResourceNotFoundException("Project not found with id: "+id);
    }

    @PutMapping("{id}")
    public ResponseEntity<String> updateProject(@RequestBody Project updatedProject,@PathVariable Long id) {
        boolean updated = service.updateAProject(updatedProject,id);
        if(updated) {
            return new ResponseEntity<>("Project with id: "+id+" has been updated",HttpStatus.OK);
        }
        throw new ResourceNotFoundException("Project not found with id: "+id);
    }

    @DeleteMapping("{id}")
    public ResponseEntity<String> deleteProject(@PathVariable Long id) {
        boolean deleted = service.deleteAProject(id);
        if(deleted) {
            return new ResponseEntity<>("Project with id: "+id+" deleted",HttpStatus.OK);
        }
        throw new DatabaseOperationException("Project could not be deleted");
    }
}
