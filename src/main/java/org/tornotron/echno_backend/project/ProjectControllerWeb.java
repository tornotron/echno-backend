package org.tornotron.echno_backend.project;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.project.dto.ProjectCreationDto;
import org.tornotron.echno_backend.project.dto.ProjectDto;
import org.tornotron.echno_backend.project.dto.ProjectSimpleDto;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/project/web")
@Validated
@Tag(
        name = "Projects",
        description = "Web-client twin of the project endpoints. Adds employee membership management "
                + "(add/remove/list by project or by employee) alongside the same create, read, "
                + "update and delete operations as the base project API. Access is gated by tenant "
                + "membership, with mutations restricted to a system admin or project manager."
)
public class ProjectControllerWeb {

    private final ProjectService service;

    private final JsonPartBinder jsonPartBinder;
    /** Logger for this class. */
    private static final Logger logger = LoggerFactory.getLogger(ProjectControllerWeb.class);

    /**
     * Constructs a ProjectController with the given ProjectService.
     *
     * @param service The service to handle project-related business logic.
     */
    public ProjectControllerWeb(ProjectService service, JsonPartBinder jsonPartBinder) {
        this.service = service;
        this.jsonPartBinder = jsonPartBinder;
    }

    /**
     * Creates a new project.
     *
     * param projectDto DTO containing the details for the new project.
     * @return A {@link ResponseEntity} with the created project's simple DTO and HTTP status 201 (Created).
     */

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Create a project",
            description = "Creates a project from a multipart request. The data part carries the "
                    + "project details as JSON and the optional attachments part carries supporting "
                    + "files. Returns the created project as a simple view."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Project created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The data part is not valid project JSON, or a field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<ProjectSimpleDto> createProject(@RequestPart("data") String data,
                                                          @RequestParam(value = "attachments", required = false) List<MultipartFile> attachments) throws JsonProcessingException {
        ProjectCreationDto dto = jsonPartBinder.read(data, ProjectCreationDto.class);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addProject(dto, attachments));
    }
    /**
     * Lists the current tenant's projects as a bare array, capped rather than paged.
     *
     * <p>This used to accept {@code pageNo} and {@code pageSize} defaulting to the first ten rows
     * and then answer with {@code page.getContent()}, so a caller that passed no parameters, which
     * is every caller the web client has, received ten projects and no indication that more
     * existed. Most of those callers are project pickers in forms, so the effect was that a user
     * could not select their own project unless it happened to hold one of the ten lowest ids, and
     * the dropdown looked complete while doing it.
     *
     * <p>The parameters are gone rather than re-tuned: keeping them on an endpoint that discards
     * the page envelope is what made the truncation silent. The read is bounded by
     * {@link UnpagedResultCap} instead and never silently truncated. Every response carries the
     * true row count in {@code X-Total-Count}, and one that did not fit also carries
     * {@code X-Result-Capped}. A caller that needs to walk past the cap has
     * {@link #readAllProjectsPaginated} for it.
     *
     * @return A {@link ResponseEntity} containing the project DTOs and the count headers.
     */
    @GetMapping()
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "List projects",
            description = "Returns the current tenant's projects as a bare array, capped at 500 "
                    + "rows. The response always carries the true total in X-Total-Count, and "
                    + "carries X-Result-Capped when rows were left out, so a caller can tell a "
                    + "complete result from a capped one. Use /paginated to page beyond the cap."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Projects returned, capped at 500 rows"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<ProjectDto>> readAllProjects() {
        logger.info("All Projects Retrieved Successfully");
        return UnpagedResultCap.respond(service.getAllProjects(0, UnpagedResultCap.MAX_ROWS));
    }

    /**
     * Lists projects as a real {@link Page}, with the paging metadata intact.
     *
     * <p>The honest counterpart to {@link #readAllProjects}: a caller gets {@code totalElements},
     * {@code totalPages} and the page index alongside the content, so a truncated result describes
     * itself. Deliberately the same shape as {@code GET /tasks/web/paginated}; consistency between
     * the two listings matters more than either choice would in isolation.
     *
     * @param pageNo   Zero-based page index.
     * @param pageSize Rows per page, clamped to the result cap.
     * @param search   Optional case-insensitive match on the project name.
     * @return A {@link ResponseEntity} containing the page of project DTOs.
     */
    @GetMapping("/paginated")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "List projects, paginated and filtered",
            description = "Returns a single page of projects with the paging metadata included, "
                    + "optionally filtered by a free-text search on the project name. pageSize is "
                    + "clamped to 500."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of projects returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<Page<ProjectDto>> readAllProjectsPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(service.getProjectsPaginated(pageNo, pageSize, search));
    }

    /**
     * Retrieves a single project by its ID.
     *
     * @param id The ID of the project to retrieve.
     * @return A {@link ResponseEntity} containing the project DTO and HTTP status 200 (OK).
     */
    @GetMapping("{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Get a project by id",
            description = "Returns a single project including its assigned employees and attachments."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Project found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No project with the given id")
    })
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
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Partially update a project",
            description = "Applies field updates from a multipart request. The data part carries the "
                    + "changed fields as JSON and the optional attachments part adds files under the "
                    + "given entityType."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Project updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The data part is not valid JSON, or a field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No project with the given id")
    })
    public ResponseEntity<ProjectSimpleDto> partialUpdateAProject(
            @RequestPart(value = "data", required = false) String data,
            @PathVariable Long id,
            @RequestParam(value = "attachments", required = false) List<MultipartFile> attachments,
            @RequestParam(value = "entityType", required = false,defaultValue = "PROJECT_ATTACHMENTS") String entityType) throws JsonProcessingException
     {
        Map<String, Object> updates = jsonPartBinder.readUpdates(data);
        return ResponseEntity.status(HttpStatus.OK).body(service.partialUpdateAProject(updates,id,attachments,entityType));
    }

//    /**
//     * Updates multiple projects in a batch.
//     *
//     * @param updates A list of DTOs containing the updates for each project.
//     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
//     */
//    @PatchMapping("/batch")
//    public ResponseEntity<ApiResponse> batchUpdateProjects(@Valid @RequestBody List<ProjectPatchDto> updates) {
//        service.batchUpdateProjects(updates);
//        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Batch update successful"));
//    }

    /**
     * Deletes a project by its ID.
     *
     * @param id The ID of the project to delete.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @DeleteMapping("{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Delete a project",
            description = "Deletes the project with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Project deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No project with the given id")
    })
    public ResponseEntity<ApiResponse> deleteProject(@PathVariable Long id) {
        service.deleteAProject(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Project with id: "+id+" has been deleted"));
    }

    @PostMapping("{projectId}/employees/{employeeId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Add an employee to a project",
            description = "Assigns the given employee to the given project's team."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employee added, updated project team returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No project or employee with the given id")
    })
    public ResponseEntity<List<EmployeeDto>> addEmployeeToProject(@PathVariable Long projectId, @PathVariable Long employeeId) {
        return ResponseEntity.status(HttpStatus.OK).body(service.addEmployeeToProject(projectId, employeeId));
    }

    @DeleteMapping("{projectId}/employees/{employeeId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Remove an employee from a project",
            description = "Removes the given employee from the given project's team."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employee removed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No project or employee with the given id")
    })
    public ResponseEntity<ApiResponse> removeEmployeeFromProject(@PathVariable Long projectId, @PathVariable Long employeeId) {
        service.removeEmployeeFromProject(projectId, employeeId);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Employee removed from project"));
    }

    @GetMapping("{projectId}/employees")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "List a project's employees",
            description = "Returns every employee assigned to the given project."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employees returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No project with the given id")
    })
    public ResponseEntity<List<EmployeeDto>> getEmployeesByProjectId(@PathVariable Long projectId) {
        return ResponseEntity.status(HttpStatus.OK).body(service.getEmployeesByProjectId(projectId));
    }

    @GetMapping("employees/{employeeId}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "List an employee's projects",
            description = "Returns every project the given employee is assigned to."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Projects returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<List<ProjectDto>> getProjectsByEmployeeId(@PathVariable Long employeeId) {
        return ResponseEntity.status(HttpStatus.OK).body(service.getProjectsByEmployeeId(employeeId));
    }

}
