package org.tornotron.echno_backend.site.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.site.model.Project;
import org.tornotron.echno_backend.site.service.ProjectService;

import java.util.List;

@RestController
@RequestMapping("/projects")
public class ProjectController {

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> createProject(@RequestBody Project project) {

        if(project.getProjectName() == null || project.getProjectName().trim().isEmpty()) {
            return new ResponseEntity<>("'projectName' is a required parameter",HttpStatus.BAD_REQUEST);
        }
        Boolean created = service.addProject(project);
        if(created) {
            return new ResponseEntity<>(project, HttpStatus.CREATED);
        }
        return new ResponseEntity<>("Project could not be created",HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @GetMapping
    public ResponseEntity<List<Project>> readAllProjects() {
        return new ResponseEntity<>(service.getAllProjects(),HttpStatus.OK);
    }

    @GetMapping("{id}")
    public ResponseEntity<?> readAProject(@PathVariable Long id) {
        Project project = service.getAProject(id);
        if(project != null) {
            return new ResponseEntity<>(project,HttpStatus.OK);
        }
        return new ResponseEntity<>("Project with id: "+id+" does not exist",HttpStatus.NOT_FOUND);
    }

    @PutMapping("{id}")
    public ResponseEntity<String> updateProject(@RequestBody Project updatedProject,@PathVariable Long id) {
        boolean updated = service.updateAProject(updatedProject,id);
        if(updated) {
            return new ResponseEntity<>("Project with id: "+id+" has been updated",HttpStatus.OK);
        }
        return new ResponseEntity<>("Project with id: "+id+" not found",HttpStatus.NOT_FOUND);
    }

    @DeleteMapping("{id}")
    public ResponseEntity<String> deleteProject(@PathVariable Long id) {
        boolean deleted = service.deleteAProject(id);
        if(deleted) {
            return new ResponseEntity<>("Project with id: "+id+" deleted",HttpStatus.OK);
        }
        return new ResponseEntity<>("Project with id: "+id+" not found",HttpStatus.NOT_FOUND);
    }
}
