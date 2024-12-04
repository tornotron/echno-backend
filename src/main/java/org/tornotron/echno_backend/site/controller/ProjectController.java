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
        boolean created = service.addProject(project);
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
}
