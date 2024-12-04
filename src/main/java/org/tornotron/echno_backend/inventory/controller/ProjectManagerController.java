//package org.tornotron.echno_backend.inventory.controller;
//
//import org.springframework.http.HttpStatus;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.PostMapping;
//import org.springframework.web.bind.annotation.RequestBody;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RestController;
//import org.tornotron.echno_backend.inventory.model.ProjectManager;
//import org.tornotron.echno_backend.inventory.service.ProjectManagerService;
//
//@RestController
//@RequestMapping("/projectManager")
//public class ProjectManagerController {
//
//    private final ProjectManagerService service;
//
//    public ProjectManagerController(ProjectManagerService service) {
//        this.service = service;
//    }
//
//    @PostMapping
//    public ResponseEntity<String> createProjectManager(@RequestBody ProjectManager projectManager) {
//        boolean created = service.addProjectManager(projectManager);
//        if (created) {
//            return new ResponseEntity<>("ProjectManager added successfully", HttpStatus.CREATED);
//        }
//        return new ResponseEntity<>("Error creating project manager",HttpStatus.INTERNAL_SERVER_ERROR);
//    }
//}
