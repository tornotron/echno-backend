package org.tornotron.echno_backend.IssueComment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentCreationDto;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentDto;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentSimpleDto;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentUpdateDto;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.common.response.ApiResponse;

import java.util.List;

@RestController
@RequestMapping("/api/v1/issues/comments/web")
@Validated
@Tag(
        name = "Issue Comments",
        description = "Web-client twin of the issue comment endpoints. Adds a single-comment read and a "
                + "lookup of every comment on an issue, alongside the same create, paginated listing "
                + "and delete operations as the base API. Access is gated by tenant membership. An "
                + "edit is restricted to the comment's own author, and a delete to a system admin or "
                + "project manager."
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant, or has no employee record in it, so the record would name nobody"),
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
    public ResponseEntity<List<IssueCommentDto>> readAllIssueComments(@Valid @ParameterObject PageQuery pageQuery) {
        Page<IssueCommentDto> issueComments = issueCommentService.getAllIssueComments(pageQuery.getPageNo(),pageQuery.getPageSize());
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

    /**
     * Corrects the text of a comment the caller wrote.
     *
     * <p>The guard is tenant membership, and the authorship check is in
     * {@link IssueCommentService#updateIssueComment}, next to the write it authorises, so the row
     * the author is read from is the row the text is written to.
     * {@code ChatControllerWeb.editMessage} is annotated the same way for the same reason.
     *
     * <p>There is no twin of this on {@link IssueCommentController}. Every guard on that
     * controller names an {@code issue-comment:*} authority, and those are bare
     * {@code resource:scope} strings that the realm has no authorization scopes to issue, so an
     * edit added there would arrive refusing every caller. Repairing that surface is the
     * phantom-guard family and belongs with it, not inside this feature.
     *
     * @param id The comment to correct.
     * @param dto The replacement text.
     * @return The updated comment, carrying the edited marker.
     */
    @PatchMapping("{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Edit an issue comment",
            description = "Changes the text of the caller's own comment and marks it edited, with the "
                    + "time of the edit. Only the comment's author may do this: a system admin or "
                    + "project manager may delete a comment but not rewrite it."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Comment updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The comment is blank or longer than 500 characters"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant, or is not the comment's author"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No comment with the given id")
    })
    public ResponseEntity<IssueCommentDto> updateIssueComment(@PathVariable Long id,
                                                              @Valid @RequestBody IssueCommentUpdateDto dto) {
        return ResponseEntity.status(HttpStatus.OK).body(issueCommentService.updateIssueComment(id, dto.getComment()));
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
