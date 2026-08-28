package org.tornotron.echno_backend.issue;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.tornotron.echno_backend.common.response.ApiResponse;

import java.util.List;
import java.util.Map;

@RestController
@Validated
@RequestMapping("/api/v1/issues")
@Tag(
        name = "Issues",
        description = "A problem or defect raised against a task, optionally with attachments and "
                + "comments. Endpoints cover reading a single issue, creating one with attachments, "
                + "partial updates and deletion. Access is gated by the issue authorities, with an "
                + "admin authority that grants all operations."
)
public class IssueController {

    private final IssueService issueService;

    private final JsonPartBinder jsonPartBinder;

    public IssueController(IssueService issueService, JsonPartBinder jsonPartBinder) {
        this.issueService = issueService;
        this.jsonPartBinder = jsonPartBinder;
    }

//    @GetMapping
//    @PreAuthorize("hasAuthority('issue:read') or hasAuthority('issue:admin')")
//    public ResponseEntity<List<IssueDto>> readAllIssues(@RequestParam(defaultValue = "0") int pageNo,
//                                                        @RequestParam(defaultValue = "10") int pageSize) {
//        Page<IssueDto> issues = issueService.getAllIssues(pageNo,pageSize);
//        return new ResponseEntity<>(issues.getContent(), HttpStatus.OK);
//
//    }


    @GetMapping("{id}")
    @PreAuthorize("hasAuthority('issue:read') or hasAuthority('issue:admin')")
    @Operation(
            summary = "Get an issue by id",
            description = "Returns a single issue including its comments and attachments."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Issue found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the issue read or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No issue with the given id")
    })
    public ResponseEntity<IssueDto> readAnIssue(@PathVariable Long id) {
        IssueDto issue = issueService.getAnIssue(id);
        return new ResponseEntity<>(issue, HttpStatus.OK);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('issue:create') or hasAuthority('issue:admin')")
    @Operation(
            summary = "Create an issue",
            description = "Creates an issue from a multipart request. The data part carries the issue "
                    + "details as JSON and the optional attachments part carries supporting files. Returns "
                    + "the created issue as a simple view."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Issue created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The data part is not valid issue JSON, or a field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the issue create or admin authority")
    })
    public ResponseEntity<IssueSimpleDto> createIssue(@RequestPart("data") String data,
                                                      @RequestParam(value = "attachments",required = false) List<MultipartFile> attachments) throws JsonProcessingException {
        IssueCreationDto dto = jsonPartBinder.read(data, IssueCreationDto.class);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(issueService.addIssue(dto,attachments));
    }

    @PatchMapping(value = "{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('issue:update') or hasAuthority('issue:admin')")
    @Operation(
            summary = "Partially update an issue",
            description = "Applies field updates from a multipart request. The data part carries the "
                    + "changed fields as JSON and the optional attachments part adds files under the "
                    + "given entityType."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Issue updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The data part is not valid JSON, or a field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the issue update or admin authority"),
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

    @DeleteMapping("{id}")
    @PreAuthorize("hasAuthority('issue:delete') or hasAuthority('issue:admin')")
    @Operation(
            summary = "Delete an issue",
            description = "Deletes the issue with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Issue deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the issue delete or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No issue with the given id")
    })
    public ResponseEntity<ApiResponse> deleteAnIssue(@PathVariable Long id) {
        issueService.deleteAnIssue(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Issue with id: " + id + " has been deleted"));
    }

}
