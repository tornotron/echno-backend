package org.tornotron.echno_backend.project;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.project.dto.ProjectCreationDto;
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
    public ResponseEntity<String> createProject(@Valid @RequestBody ProjectCreationDto projectDto) {
        service.addProject(projectDto);
        return ResponseEntity.status(HttpStatus.CREATED).body("Project Added Successfully");
    }

    @GetMapping
    public ResponseEntity<List<ProjectDto>> readAllProjects() {
        return new ResponseEntity<>(service.getAllProjects(),HttpStatus.OK);
    }

    @GetMapping("{id}")
    public ResponseEntity<?> readAProject(@PathVariable Long id) {
        ProjectDto project = service.getAProject(id);
        return new ResponseEntity<>(project,HttpStatus.OK);
    }

    @PutMapping("{id}")
    public ResponseEntity<String> updateProject(@RequestBody Project updatedProject,@PathVariable Long id) {
        service.updateAProject(updatedProject,id);
        return new ResponseEntity<>("Project with id: "+id+" has been updated",HttpStatus.OK);
    }

    @DeleteMapping("{id}")
    public ResponseEntity<String> deleteProject(@PathVariable Long id) {
        service.deleteAProject(id);
        return new ResponseEntity<>("Project with id: "+id+" deleted",HttpStatus.OK);
    }
}
