package org.tornotron.echno_backend.issue;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.issue.dto.IssueCreationDto;

@RestController
@Validated
@RequestMapping("/api/v1/issues")
public class IssueController {

    private final IssueService issueService;

    public IssueController(IssueService issueService) {
        this.issueService = issueService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse> createIssue(@Valid @RequestBody IssueCreationDto issueCreationDto) {
        issueService.addIssue(issueCreationDto);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse("Issue created successfully"));
    }
}
