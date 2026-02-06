package org.tornotron.echno_backend.project;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.project.dto.ProjectCreationDto;
import org.tornotron.echno_backend.project.dto.ProjectDto;
import org.tornotron.echno_backend.project.dto.ProjectPatchDto;
import org.tornotron.echno_backend.project.dto.ProjectSimpleDto;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/project/web")
@Validated
public class ProjectControllerWeb {

    private final ProjectService service;

    private final ObjectMapper objectMapper;
    /** Logger for this class. */
    private static final Logger logger = LoggerFactory.getLogger(ProjectControllerWeb.class);

    /**
     * Constructs a ProjectController with the given ProjectService.
     *
     * @param service The service to handle project-related business logic.
     */
    public ProjectControllerWeb(ProjectService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a new project.
     *
     * param projectDto DTO containing the details for the new project.
     * @return A {@link ResponseEntity} with the created project's simple DTO and HTTP status 201 (Created).
     */

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
//    @PreAuthorize("hasAuthority('project:create') or hasAuthority('project:admin')")
    public ResponseEntity<ProjectSimpleDto> createProject(@RequestPart("data") @Valid String data,
                                                          @RequestParam(value = "attachments", required = false) List<MultipartFile> attachments) throws JsonProcessingException {
        ProjectCreationDto dto = objectMapper.readValue(data, ProjectCreationDto.class);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addProject(dto, attachments));
    }
    /**
     * Retrieves a paginated list of all projects.
     *
     * @param pageNo   The page number to retrieve (default is 0).
     * @param pageSize The number of projects per page (default is 10).
     * @return A {@link ResponseEntity} containing the list of project DTOs and HTTP status 200 (OK).
     */
    @GetMapping
//    @PreAuthorize("hasAuthority('project:read') or hasAuthority('project:admin')")
    public ResponseEntity<List<ProjectDto>> readAllProjects(@RequestParam(defaultValue = "0") int pageNo,
                                                            @RequestParam(defaultValue = "10") int pageSize) {
        Page<ProjectDto> projects = service.getAllProjects(pageNo,pageSize);
        logger.info("All Projects Retrieved Successfully");
        return new ResponseEntity<>(projects.getContent(),HttpStatus.OK);
    }

    /**
     * Retrieves a single project by its ID.
     *
     * @param id The ID of the project to retrieve.
     * @return A {@link ResponseEntity} containing the project DTO and HTTP status 200 (OK).
     */
    @GetMapping("{id}")
//    @PreAuthorize("hasAuthority('project:read') or hasAuthority('project:admin')")
    public ResponseEntity<?> readAProject(@PathVariable Long id) {
        ProjectDto project = service.getAProject(id);
        return new ResponseEntity<>(project,HttpStatus.OK);
    }

    /**
     * Partially updates an existing project.
     *
     * @param id      The ID of the project to update.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @PatchMapping(value = "{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
//    @PreAuthorize("hasAuthority('project:update') or hasAuthority('project:admin')")
    public ResponseEntity<ProjectSimpleDto> partialUpdateAProject(
            @RequestPart(value = "data", required = false) String data,
            @PathVariable Long id,
            @RequestParam(value = "attachments", required = false) List<MultipartFile> attachments,
            @RequestParam(value = "entityType", required = false,defaultValue = "TASK_ATTACHMENTS") String entityType) throws JsonProcessingException
     {
        Map<String, Object> updates = data != null
                ? objectMapper.readValue(data, new TypeReference<>() {}) : Map.of();
        return ResponseEntity.status(HttpStatus.OK).body(service.partialUpdateAProject(updates,id,attachments,entityType));
    }

    /**
     * Updates multiple projects in a batch.
     *
     * @param updates A list of DTOs containing the updates for each project.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @PatchMapping("/batch")
//    @PreAuthorize("hasAuthority('project:update') or hasAuthority('project:admin')")
    public ResponseEntity<ApiResponse> batchUpdateProjects(@Valid @RequestBody List<ProjectPatchDto> updates) {
        service.batchUpdateProjects(updates);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Batch update successful"));
    }

    /**
     * Deletes a project by its ID.
     *
     * @param id The ID of the project to delete.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @DeleteMapping("{id}")
//    @PreAuthorize("hasAuthority('project:delete') or hasAuthority('project:admin')")
    public ResponseEntity<ApiResponse> deleteProject(@PathVariable Long id) {
        service.deleteAProject(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Project with id: "+id+" has been deleted"));
    }

    /**
     * Retrieves the organization ID for a given project ID.
     *
     * @param id The ID of the project.
     * @return A {@link ResponseEntity} containing the organization ID and HTTP status 200 (OK).
     */
    @GetMapping("{id}/organization")
//    @PreAuthorize("hasAuthority('project:read') or hasAuthority('project:admin')")
    public ResponseEntity<Long> getOrganizationIdByProjectId(@PathVariable Long id) {
        Long organizationId = service.getOrganizationIdByProjectId(id);
        return new ResponseEntity<>(organizationId, HttpStatus.OK);
    }

}
