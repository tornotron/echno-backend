package org.tornotron.echno_backend.project;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springdoc.core.annotations.ParameterObject;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.common.payload.JsonPartBinder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.project.dto.ProjectCreationDto;
import org.tornotron.echno_backend.project.dto.ProjectDto;
import org.tornotron.echno_backend.project.dto.ProjectPatchDto;
import org.tornotron.echno_backend.project.dto.ProjectSimpleDto;

import java.util.List;
import java.util.Map;

/**
 * REST controller for managing projects.
 * Provides endpoints for creating, reading, updating, and deleting projects.
 */
@RestController
@RequestMapping("/api/v1/project")
@Validated
@Tag(
        name = "Projects",
        description = "Construction projects and their team assignments. Endpoints cover creating a "
                + "project with attachments, browsing and reading projects, batch updates, deletion, "
                + "and adding or removing the employees assigned to a project. Access is gated by the "
                + "project authorities, with an admin authority that grants all operations."
)
public class ProjectController {

    private final ProjectService service;
    private final JsonPartBinder jsonPartBinder;
    /** Logger for this class. */
    private static final Logger logger = LoggerFactory.getLogger(ProjectController.class);

    /**
     * Constructs a ProjectController with the given ProjectService.
     *
     * @param service The service to handle project-related business logic.
     * @param jsonPartBinder Reads and validates the JSON part of a multipart request.
     */
    public ProjectController(ProjectService service, JsonPartBinder jsonPartBinder) {
        this.service = service;
        this.jsonPartBinder = jsonPartBinder;
    }

    /**
     * Creates a new project.
     *
     *  projectDto DTO containing the details for the new project.
     * @return A {@link ResponseEntity} with the created project's simple DTO and HTTP status 201 (Created).
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('project:create') or hasAuthority('project:admin')")
    @Operation(
            summary = "Create a project",
            description = "Creates a project from a multipart request. The data part carries the project "
                    + "details as JSON and the optional attachments part carries supporting files. "
                    + "Returns the created project as a simple view without its tasks or team."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Project created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The data part is not valid project JSON, or a field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the project create or admin authority")
    })
    public ResponseEntity<ProjectSimpleDto> createProject(
            @Parameter(schema = @Schema(implementation = ProjectCreationDto.class))
            @RequestPart("data") String data,
            @RequestParam(value = "attachments", required = false) List<MultipartFile> attachments) throws JsonProcessingException {
        ProjectCreationDto dto = jsonPartBinder.read(data, ProjectCreationDto.class);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addProject(dto, attachments));
    }


    /**
     * Retrieves a paginated list of all projects.
     *
     * @param pageQuery Page index and page size, bounded by {@link PageQuery}.
     * @return A {@link ResponseEntity} containing the list of project DTOs and HTTP status 200 (OK).
     */
    @GetMapping
    @PreAuthorize("hasAuthority('project:read') or hasAuthority('project:admin')")
    @Operation(
            summary = "List projects",
            description = "Returns a single page of projects. The pageNo and pageSize parameters control "
                    + "paging; only the page content is returned, without paging metadata."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of projects returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the project read or admin authority")
    })
    public ResponseEntity<List<ProjectDto>> readAllProjects(@Valid @ParameterObject PageQuery pageQuery) {
          Page<ProjectDto> projects = service.getAllProjects(pageQuery.getPageNo(),pageQuery.getPageSize());
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
    @PreAuthorize("hasAuthority('project:read') or hasAuthority('project:admin')")
    @Operation(
            summary = "Get a project by id",
            description = "Returns a single project including its team, tasks, attachments and progress."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Project found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the project read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No project with the given id")
    })
    public ResponseEntity<?> readAProject(@PathVariable Long id) {
        ProjectDto project = service.getAProject(id);
        return new ResponseEntity<>(project,HttpStatus.OK);
    }

    /**
     * Partially updates an existing project.
     *
     * @param updates A map of fields to update.
     * @param id      The ID of the project to update.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
//    @PatchMapping("{id}")
//    @PreAuthorize("hasAuthority('project:update') or hasAuthority('project:admin')")
//    public ResponseEntity<ApiResponse> partialUpdateAProject(@RequestBody Map<String,Object> updates,@PathVariable Long id) {
//        service.partialUpdateAProject(updates,id);
//        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Project with id: "+id+" updated"));
//    }

    /**
     * Updates multiple projects in a batch.
     *
     * @param updates A list of DTOs containing the updates for each project.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @PatchMapping("/batch")
    @PreAuthorize("hasAuthority('project:update') or hasAuthority('project:admin')")
    @Operation(
            summary = "Batch update projects",
            description = "Applies partial updates to several projects in one call. Each entry names a "
                    + "project id and the map of fields to change on that project."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Batch update applied"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "One of the update entries failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the project update or admin authority")
    })
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
    @PreAuthorize("hasAuthority('project:delete') or hasAuthority('project:admin')")
    @Operation(
            summary = "Delete a project",
            description = "Deletes the project with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Project deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the project delete or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No project with the given id")
    })
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
    @PreAuthorize("hasAuthority('project:read') or hasAuthority('project:admin')")
    @Operation(
            summary = "Get the organization id for a project",
            description = "Returns the id of the organization that owns the given project."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Organization id returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the project read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No project with the given id")
    })
    public ResponseEntity<Long> getOrganizationIdByProjectId(@PathVariable Long id) {
        Long organizationId = service.getOrganizationIdByProjectId(id);
        return new ResponseEntity<>(organizationId, HttpStatus.OK);
    }

    @PostMapping("{projectId}/employees/{employeeId}")
    @PreAuthorize("hasAuthority('project:update') or hasAuthority('project:admin')")
    @Operation(
            summary = "Assign an employee to a project",
            description = "Adds the given employee to the project team and returns the project's updated "
                    + "list of assigned employees."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employee assigned, updated team returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the project update or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No project or employee with the given id")
    })
    public ResponseEntity<List<EmployeeDto>> addEmployeeToProject(@PathVariable Long projectId, @PathVariable Long employeeId) {
        return ResponseEntity.status(HttpStatus.OK).body(service.addEmployeeToProject(projectId, employeeId));
    }

    @DeleteMapping("{projectId}/employees/{employeeId}")
    @PreAuthorize("hasAuthority('project:update') or hasAuthority('project:admin')")
    @Operation(
            summary = "Remove an employee from a project",
            description = "Removes the given employee from the project team."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employee removed from the project"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the project update or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No project or employee with the given id")
    })
    public ResponseEntity<ApiResponse> removeEmployeeFromProject(@PathVariable Long projectId, @PathVariable Long employeeId) {
        service.removeEmployeeFromProject(projectId, employeeId);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Employee removed from project"));
    }

    @GetMapping("{projectId}/employees")
    @PreAuthorize("hasAuthority('project:read') or hasAuthority('project:admin')")
    @Operation(
            summary = "List the employees on a project",
            description = "Returns the employees currently assigned to the given project."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Assigned employees returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the project read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No project with the given id")
    })
    public ResponseEntity<List<EmployeeDto>> getEmployeesByProjectId(@PathVariable Long projectId) {
        return ResponseEntity.status(HttpStatus.OK).body(service.getEmployeesByProjectId(projectId));
    }
}