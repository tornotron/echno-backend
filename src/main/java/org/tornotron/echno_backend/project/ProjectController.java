package org.tornotron.echno_backend.project;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.project.dto.ProjectCreationDto;
import org.tornotron.echno_backend.project.dto.ProjectDto;
import org.tornotron.echno_backend.project.dto.ProjectPatchDto;

import java.util.List;
import java.util.Map;

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
    public ResponseEntity<List<ProjectDto>> readAllProjects(@RequestParam(defaultValue = "0") int pageNo,
                                                                 @RequestParam(defaultValue = "10") int pageSize) {
          Page<ProjectDto> projects = service.getAllProjects(pageNo,pageSize);
        return new ResponseEntity<>(projects.getContent(),HttpStatus.OK);
    }

    @GetMapping("{id}")
    public ResponseEntity<?> readAProject(@PathVariable Long id) {
        ProjectDto project = service.getAProject(id);
        return new ResponseEntity<>(project,HttpStatus.OK);
    }

    @PatchMapping("{id}")
    public ResponseEntity<String> partialUpdateAProject(@RequestBody Map<String,Object> updates,@PathVariable Long id) {
        service.partialUpdateAProject(updates,id);
        return new ResponseEntity<>("Project with id: "+id+" has been updated",HttpStatus.OK);
    }

    @PatchMapping("/batch")
    public ResponseEntity<String> batchUpdateProjects(@Valid @RequestBody List<ProjectPatchDto> updates) {
        service.batchUpdateProjects(updates);
        return new ResponseEntity<>("Batch update successful",HttpStatus.OK);
    }

    @DeleteMapping("{id}")
    public ResponseEntity<String> deleteProject(@PathVariable Long id) {
        service.deleteAProject(id);
        return new ResponseEntity<>("Project with id: "+id+" deleted",HttpStatus.OK);
    }
}
