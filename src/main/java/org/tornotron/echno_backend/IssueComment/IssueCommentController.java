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
@RequestMapping("/api/v1/issues/comments")
@Validated
@Tag(
        name = "Issue Comments",
        description = "A comment left on an issue, carrying the comment text, its author and a "
                + "timestamp. Endpoints cover creation, paginated listing and deletion, gated by the "
                + "issue-comment authorities, with an admin authority that grants all operations."
)
public class IssueCommentController {

    private final IssueCommentService issueCommentService;

    public IssueCommentController(IssueCommentService issueCommentService) {
        this.issueCommentService = issueCommentService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('issue-comment:create') or hasAuthority('issue-comment:admin')")
    @Operation(
            summary = "Create an issue comment",
            description = "Adds a comment to the given issue."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Comment created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the issue-comment create or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No issue with the given id")
    })
    public ResponseEntity<IssueCommentSimpleDto> createIssueComment(@Valid @RequestBody IssueCommentCreationDto issueCommentCreationDto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(issueCommentService.addIssueComment(issueCommentCreationDto));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('issue-comment:read') or hasAuthority('issue-comment:admin')")
    @Operation(
            summary = "List issue comments",
            description = "Returns a single page of issue comments. The pageNo and pageSize parameters "
                    + "control paging; only the page content is returned, without paging metadata."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of comments returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the issue-comment read or admin authority")
    })
    public ResponseEntity<List<IssueCommentDto>> readAllIssueComments(@RequestParam(defaultValue = "0") int pageNo,
                                                                      @RequestParam(defaultValue = "10") int pageSize) {
        Page<IssueCommentDto> issueComments = issueCommentService.getAllIssueComments(pageNo,pageSize);
        return ResponseEntity.status(HttpStatus.OK).body(issueComments.getContent());
    }

    @DeleteMapping("{id}")
    @PreAuthorize("hasAuthority('issue-comment:delete') or hasAuthority('issue-comment:admin')")
    @Operation(
            summary = "Delete an issue comment",
            description = "Deletes the comment with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Comment deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the issue-comment delete or admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No comment with the given id")
    })
    public ResponseEntity<ApiResponse> deleteAnIssueComment(@PathVariable Long id) {
        issueCommentService.deleteAnIssueComment(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("IssueComment with id: "+id+" deleted successfully with"));
    }
}
