package org.tornotron.echno_backend.IssueComment;

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
public class IssueCommentController {

    private final IssueCommentService issueCommentService;

    public IssueCommentController(IssueCommentService issueCommentService) {
        this.issueCommentService = issueCommentService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('issue-comment:create') or hasAuthority('issue-comment:admin')")
    public ResponseEntity<IssueCommentSimpleDto> createIssueComment(@Valid @RequestBody IssueCommentCreationDto issueCommentCreationDto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(issueCommentService.addIssueComment(issueCommentCreationDto));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('issue-comment:read') or hasAuthority('issue-comment:admin')")
    public ResponseEntity<List<IssueCommentDto>> readAllIssueComments(@RequestParam(defaultValue = "0") int pageNo,
                                                                      @RequestParam(defaultValue = "10") int pageSize) {
        Page<IssueCommentDto> issueComments = issueCommentService.getAllIssueComments(pageNo,pageSize);
        return ResponseEntity.status(HttpStatus.OK).body(issueComments.getContent());
    }

    @DeleteMapping("{id}")
    @PreAuthorize("hasAuthority('issue-comment:delete') or hasAuthority('issue-comment:admin')")
    public ResponseEntity<ApiResponse> deleteAnIssueComment(@PathVariable Long id) {
        issueCommentService.deleteAnIssueComment(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("IssueComment with id: "+id+" deleted successfully with"));
    }
}
