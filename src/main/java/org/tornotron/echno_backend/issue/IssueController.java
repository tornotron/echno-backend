package org.tornotron.echno_backend.issue;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.issue.dto.IssueCreationDto;
import org.tornotron.echno_backend.issue.dto.IssueDto;
import org.tornotron.echno_backend.issue.dto.IssueSimpleDto;

import java.util.List;
import java.util.Map;

@RestController
@Validated
@RequestMapping("/api/v1/issues")
public class IssueController {

    private final IssueService issueService;

    public IssueController(IssueService issueService) {
        this.issueService = issueService;
    }

    @GetMapping
    public ResponseEntity<List<IssueDto>> readAllIssues(@RequestParam(defaultValue = "0") int pageNo,
                                                        @RequestParam(defaultValue = "10") int pageSize) {
        Page<IssueDto> issues = issueService.getAllIssues(pageNo,pageSize);
        return new ResponseEntity<>(issues.getContent(), HttpStatus.OK);

    }

    @PostMapping
    public ResponseEntity<IssueSimpleDto> createIssue(@Valid @RequestBody IssueCreationDto issueCreationDto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(issueService.addIssue(issueCreationDto));
    }

    @PatchMapping("{id}")
    public ResponseEntity<IssueSimpleDto> partialUpdateAnIssue(@RequestBody Map<String, Object> updates, @PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(issueService.partialUpdateAnIssue(updates, id));
    }

}
