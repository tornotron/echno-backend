package org.tornotron.echno_backend.issue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
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

import java.util.List;
import java.util.Map;

@RestController
@Validated
@RequestMapping("/api/v1/issues/web")
public class IssueControllerWeb {

    private final IssueService issueService;
    private final ObjectMapper objectMapper;

    public IssueControllerWeb(IssueService issueService,ObjectMapper objectMapper) {
        this.issueService = issueService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('issue:read') or hasAuthority('issue:admin')")
    public ResponseEntity<List<IssueDto>> readAllIssues(@RequestParam(defaultValue = "0") int pageNo,
                                                        @RequestParam(defaultValue = "10") int pageSize) {
        Page<IssueDto> issues = issueService.getAllIssues(pageNo,pageSize);
        return new ResponseEntity<>(issues.getContent(), HttpStatus.OK);

    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('issue:create') or hasAuthority('issue:admin')")
    public ResponseEntity<IssueSimpleDto> createIssue(@RequestPart("data") @Valid String data,
                                                      @RequestParam(value = "attachments",required = false) List<MultipartFile> attachments) throws JsonProcessingException {
        IssueCreationDto dto = objectMapper.readValue(data, IssueCreationDto.class);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(issueService.addIssue(dto,attachments));
    }

    @PatchMapping("{id}")
    @PreAuthorize("hasAuthority('issue:update') or hasAuthority('issue:admin')")
    public ResponseEntity<IssueSimpleDto> partialUpdateAnIssue(@RequestBody Map<String, Object> updates, @PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(issueService.partialUpdateAnIssue(updates, id));
    }

}
