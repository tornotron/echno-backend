package org.tornotron.echno_backend.issue;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.common.payload.JsonPartBinder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.issue.dto.IssueCreationDto;
import org.tornotron.echno_backend.issue.dto.IssueDto;
import org.tornotron.echno_backend.issue.dto.IssueSimpleDto;
import org.tornotron.echno_backend.issue.dto.IssueStatsDto;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;

import java.util.List;
import java.util.Map;

@RestController
@Validated
@RequestMapping("/api/v1/issues/web")
@Tag(
        name = "Issues",
        description = "Web-client twin of the issue endpoints. Adds unpaginated and paginated listing "
                + "with search/status/type filters, dashboard stats, and lookups by project or task, "
                + "alongside the same create, update and delete operations as the base issue API. "
                + "Access is gated by tenant membership, with update and delete restricted to a "
                + "system admin or project manager."
)
public class IssueControllerWeb {

    private final IssueService issueService;
    private final JsonPartBinder jsonPartBinder;

    public IssueControllerWeb(IssueService issueService,JsonPartBinder jsonPartBinder) {
        this.issueService = issueService;
        this.jsonPartBinder = jsonPartBinder;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "List all issues",
            description = "Returns at most 500 rows. X-Total-Count carries the true total and X-Result-Capped is set when rows were left out; use the paginated variant for a complete result."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Issues returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<IssueDto>> readAllIssues() {
        return UnpagedResultCap.respond(issueService.getAllIssuesPaginated(
                0, UnpagedResultCap.MAX_ROWS, null, null, null, null, null, null));
    }

    @GetMapping("/paginated")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "List issues, paginated and filtered",
            description = "Returns a single page of issues, optionally filtered by project, a free-text "
                    + "search on title and description, status, type, the assigned employee, or the "
                    + "employee who created it."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of issues returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<Page<IssueDto>> readAllIssuesPaginated(
            @Valid @ParameterObject PageQuery pageQuery,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) Long creatorId) {
        Page<IssueDto> issues = issueService.getAllIssuesPaginated(pageQuery.getPageNo(), pageQuery.getPageSize(), projectId, search, status, type, assigneeId, creatorId);
        return new ResponseEntity<>(issues, HttpStatus.OK);
    }

    @GetMapping("/stats")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Get issue counts",
            description = "Returns the total matching issue count and a per-status breakdown, computed "
                    + "over the same project/search/type filters as the paginated listing, so the "
                    + "dashboard stats stay accurate under server-side paging."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Stats returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<IssueStatsDto> readIssueStats(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String type) {
        return new ResponseEntity<>(issueService.getIssueStats(projectId, search, type), HttpStatus.OK);
    }

    @GetMapping("/project/{projectId}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "List issues for a project",
            description = "Returns every issue raised against tasks belonging to the given project."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Issues returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No project with the given id")
    })
    public ResponseEntity<List<IssueDto>> readAllIssuesOfProject(@PathVariable Long projectId){
        return ResponseEntity.status(HttpStatus.OK).body(issueService.getAllIssuesByProjectId(projectId));
    }

    @GetMapping("{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Get an issue by id",
            description = "Returns a single issue including its comments and attachments."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Issue found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No issue with the given id")
    })
    public ResponseEntity<IssueDto> readAnIssue(@PathVariable Long id) {
        IssueDto issue = issueService.getAnIssue(id);
        return new ResponseEntity<>(issue, HttpStatus.OK);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Create an issue",
            description = "Creates an issue from a multipart request. The data part carries the issue "
                    + "details as JSON and the optional attachments part carries supporting files. Returns "
                    + "the created issue as a simple view."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Issue created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The data part is not valid issue JSON, or a field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<IssueSimpleDto> createIssue(@RequestPart("data") String data,
                                                      @RequestParam(value = "attachments",required = false) List<MultipartFile> attachments) throws JsonProcessingException {
        IssueCreationDto dto = jsonPartBinder.read(data, IssueCreationDto.class);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(issueService.addIssue(dto,attachments));
    }

    @PatchMapping(value = "{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Partially update an issue",
            description = "Applies field updates from a multipart request. The data part carries the "
                    + "changed fields as JSON and the optional attachments part adds files under the "
                    + "given entityType."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Issue updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The data part is not valid JSON, or a field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No issue with the given id")
    })
    public ResponseEntity<IssueSimpleDto> partialUpdateAnIssue(
            @RequestPart(value = "data", required = false) String data,
            @PathVariable Long id,
            @RequestParam(value = "attachments", required = false) List<MultipartFile> attachments,
            @RequestParam(value = "entityType", required = false, defaultValue = "ISSUE_ATTACHMENTS") String entityType) throws JsonProcessingException {
        Map<String, Object> updates = jsonPartBinder.readUpdates(data);
        return ResponseEntity.status(HttpStatus.OK)
                .body(issueService.partialUpdateAnIssue(updates, id, attachments, entityType));
    }

    @GetMapping("/taskId/{taskId}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "List issues for a task",
            description = "Returns every issue raised against the given task."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Issues returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No task with the given id")
    })
    public ResponseEntity<List<IssueDto>> readAllIssuesForTask(@PathVariable Long taskId){
        return ResponseEntity.status(HttpStatus.OK).body(issueService.getAllIssuesByTaskId(taskId));
    }

    @DeleteMapping("{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Delete an issue",
            description = "Deletes the issue with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Issue deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No issue with the given id")
    })
    public ResponseEntity<ApiResponse> deleteAnIssue(@PathVariable Long id) {
        issueService.deleteAnIssue(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Issue with id: " + id + " has been deleted"));
    }

}
