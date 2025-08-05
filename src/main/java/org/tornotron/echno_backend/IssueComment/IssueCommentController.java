package org.tornotron.echno_backend.IssueComment;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentCreationDto;
import org.tornotron.echno_backend.common.response.ApiResponse;

@RestController
@RequestMapping("/api/v1/issues/comments")
@Validated
public class IssueCommentController {

    private final IssueCommentService issueCommentService;

    public IssueCommentController(IssueCommentService issueCommentService) {
        this.issueCommentService = issueCommentService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse> createIssueComment(@Valid @RequestBody IssueCommentCreationDto issueCommentCreationDto) {
        issueCommentService.addIssueComment(issueCommentCreationDto);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse("Issue Comment Created Successfully"));
    }
}
