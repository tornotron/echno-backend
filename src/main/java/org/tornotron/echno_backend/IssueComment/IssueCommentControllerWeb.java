package org.tornotron.echno_backend.IssueComment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentCreationDto;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentDto;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentSimpleDto;
import org.tornotron.echno_backend.common.response.ApiResponse;

import java.util.List;

@RestController
@RequestMapping("/api/v1/issues/comments/web")
@Validated
@Tag(
        name = "Issue Comments",
        description = "Web-client twin of the issue comment endpoints. Adds a single-comment read and a "
                + "lookup of every comment on an issue, alongside the same create, paginated listing "
                + "and delete operations as the base API. Access is gated by tenant membership, with "
                + "delete restricted to a system admin or project manager."
)
public class IssueCommentControllerWeb {

    private final IssueCommentService issueCommentService;

    public IssueCommentControllerWeb(IssueCommentService issueCommentService) {
        this.issueCommentService = issueCommentService;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Create an issue comment",
            description = "Adds a comment to the given issue."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Comment created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No issue with the given id")
    })
    public ResponseEntity<IssueCommentSimpleDto> createIssueComment(@Valid @RequestBody IssueCommentCreationDto issueCommentCreationDto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(issueCommentService.addIssueComment(issueCommentCreationDto));
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "List issue comments",
            description = "Returns a single page of issue comments. The pageNo and pageSize parameters "
                    + "control paging; only the page content is returned, without paging metadata."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of comments returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<IssueCommentDto>> readAllIssueComments(@RequestParam(defaultValue = "0") int pageNo,
                                                                      @RequestParam(defaultValue = "10") int pageSize) {
        Page<IssueCommentDto> issueComments = issueCommentService.getAllIssueComments(pageNo,pageSize);
        return ResponseEntity.status(HttpStatus.OK).body(issueComments.getContent());
    }

    @GetMapping("{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Get an issue comment by id",
            description = "Returns a single issue comment."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Comment found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No comment with the given id")
    })
    public ResponseEntity<IssueCommentDto> getAnIssueComment(@PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.OK).body(issueCommentService.getAnIssueComment(id));
    }

    @GetMapping("/issueId/{issueId}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "List comments for an issue",
            description = "Returns every comment left on the given issue."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Comments returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No issue with the given id")
    })
    public ResponseEntity<List<IssueCommentDto>> getAllIssueCommentsByIssueId(@PathVariable Long issueId) {
        return ResponseEntity.status(HttpStatus.OK).body(issueCommentService.getAllIssueCommentsByIssueId(issueId));
    }

    @DeleteMapping("{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Delete an issue comment",
            description = "Deletes the comment with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Comment deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No comment with the given id")
    })
    public ResponseEntity<ApiResponse> deleteAnIssueComment(@PathVariable Long id) {
        issueCommentService.deleteAnIssueComment(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("IssueComment with id: "+id+" deleted successfully with"));
    }
}
