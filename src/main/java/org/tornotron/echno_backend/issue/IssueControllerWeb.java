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
import org.tornotron.echno_backend.common.response.ApiResponse;

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
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<List<IssueDto>> readAllIssues() {
        List<IssueDto> issues = issueService.getAllIssues();
        return new ResponseEntity<>(issues, HttpStatus.OK);
    }

    @GetMapping("/paginated")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<Page<IssueDto>> readAllIssuesPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize) {
        Page<IssueDto> issues = issueService.getAllIssuesPaginated(pageNo, pageSize);
        return new ResponseEntity<>(issues, HttpStatus.OK);
    }

    @GetMapping("/project/{projectId}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<List<IssueDto>> readAllIssuesOfProject(@PathVariable Long projectId){
        return ResponseEntity.status(HttpStatus.OK).body(issueService.getAllIssuesByProjectId(projectId));
    }

    @GetMapping("{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<IssueDto> readAnIssue(@PathVariable Long id) {
        IssueDto issue = issueService.getAnIssue(id);
        return new ResponseEntity<>(issue, HttpStatus.OK);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<IssueSimpleDto> createIssue(@RequestPart("data") @Valid String data,
                                                      @RequestParam(value = "attachments",required = false) List<MultipartFile> attachments) throws JsonProcessingException {
        IssueCreationDto dto = objectMapper.readValue(data, IssueCreationDto.class);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(issueService.addIssue(dto,attachments));
    }

    @PatchMapping(value = "{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    public ResponseEntity<IssueSimpleDto> partialUpdateAnIssue(
            @RequestPart(value = "data", required = false) String data,
            @PathVariable Long id,
            @RequestParam(value = "attachments", required = false) List<MultipartFile> attachments,
            @RequestParam(value = "entityType", required = false, defaultValue = "ISSUE_ATTACHMENTS") String entityType) throws JsonProcessingException {
        Map<String, Object> updates = data != null
                ? objectMapper.readValue(data, new com.fasterxml.jackson.core.type.TypeReference<>() {}) : Map.of();
        return ResponseEntity.status(HttpStatus.OK)
                .body(issueService.partialUpdateAnIssue(updates, id, attachments, entityType));
    }

    @GetMapping("/taskId/{taskId}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<List<IssueDto>> readAllIssuesForTask(@PathVariable Long taskId){
        return ResponseEntity.status(HttpStatus.OK).body(issueService.getAllIssuesByTaskId(taskId));
    }

    @DeleteMapping("{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    public ResponseEntity<ApiResponse> deleteAnIssue(@PathVariable Long id) {
        issueService.deleteAnIssue(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Issue with id: " + id + " has been deleted"));
    }

}
