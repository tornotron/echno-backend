package org.tornotron.echno_backend.issue;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.issue.mapper.IssueMapper;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.issue.dto.IssueCreationDto;
import org.tornotron.echno_backend.issue.dto.IssueDto;
import org.tornotron.echno_backend.issue.dto.IssueSimpleDto;
import org.tornotron.echno_backend.issue.enums.IssueStatus;
import org.tornotron.echno_backend.issue.enums.IssueType;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.task.TaskRepository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class IssueService {

    private static final String ISSUES_FOLDER = "issues";

    private final IssueRepository issueRepository;
    private final TaskRepository taskRepository;
    private final AttachmentService attachmentService;
    private final IssueMapper issueMapper;
    private final EmployeeRepository employeeRepository;

    public IssueService(IssueRepository issueRepository, TaskRepository taskRepository, AttachmentService attachmentService, IssueMapper issueMapper, EmployeeRepository employeeRepository) {
        this.issueRepository = issueRepository;
        this.taskRepository = taskRepository;
        this.attachmentService = attachmentService;
        this.issueMapper = issueMapper;
        this.employeeRepository = employeeRepository;
    }

    @Transactional
    public IssueSimpleDto addIssue(IssueCreationDto issueCreationDto, List<MultipartFile> attachments) {
        Task task = taskRepository.findByIdAndOrganization_Id(issueCreationDto.getTaskId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Task with ID " + issueCreationDto.getTaskId() + " was not found in this organization"));
        Employee creator = employeeRepository.findByIdAndOrganizationId(issueCreationDto.getCreatedById(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Creator (employee) with ID " + issueCreationDto.getCreatedById() + " was not found in this organization"));
        Issue issue = new Issue();
        issue.setTitle(issueCreationDto.getTitle());
        issue.setDescription(issueCreationDto.getDescription());
        issue.setType(IssueType.valueOf(issueCreationDto.getType()));
        issue.setStatus(IssueStatus.valueOf(issueCreationDto.getStatus()));
        issue.setCreatedBy(creator);
        issue.setTask(task);
        issue.setOrganization(task.getOrganization());

        if (issueCreationDto.getAssignedToId() != null) {
            Employee assignee = employeeRepository.findByIdAndOrganizationId(issueCreationDto.getAssignedToId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Assignee (employee) with ID " + issueCreationDto.getAssignedToId() + " was not found in this organization"));
            issue.setAssignedTo(assignee);
        }

        Issue savedIssue = issueRepository.save(issue);

        if (attachments != null && !attachments.isEmpty()) {
            List<Attachment> savedAttachments = attachmentService.uploadAttachments(attachments, "ISSUE", savedIssue.getId(), ISSUES_FOLDER);
            for(Attachment attachment : savedAttachments) {
                savedIssue.addAttachment(attachment);
            }
            savedIssue = issueRepository.save(savedIssue);
        }

        return issueMapper.toSimpleDto(savedIssue);
    }

    @Transactional(readOnly = true)
    public List<IssueDto> getAllIssues() {
        return issueRepository.findAll().stream()
                .map(issue -> issueMapper.toDto(issue))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<IssueDto> getAllIssuesByTaskId(Long taskId) {
        return issueRepository.findAllByTask_IdAndOrganization_Id(taskId,TenantContext.getCurrentOrgId()).stream()
                .map(issue -> issueMapper.toDto(issue))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<IssueDto> getAllIssuesByProjectId(Long projectId) {
        return issueRepository.findAllByTask_Project_IdAndOrganization_Id(projectId,TenantContext.getCurrentOrgId()).stream()
                .map(issue -> issueMapper.toDto(issue))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public IssueDto getAnIssue(Long id) {
        IssueDto issueDto = issueRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .map(issue -> issueMapper.toDto(issue))
                .orElse(null);
        if (issueDto == null) {
            throw new ResourceNotFoundException("Issue with ID " + id + " was not found in this organization");
        }
        return issueDto;
    }

    @Transactional
    public IssueSimpleDto partialUpdateAnIssue(Map<String, Object> updates, Long id, List<MultipartFile> attachments, String entityType) {
        Issue issue = issueRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Issue with ID " + id + " was not found in this organization"));
        partialUpdateAnIssue(updates, issue);

        if (attachments != null) {
            for (MultipartFile att : attachments) {
                Attachment attachment = attachmentService.uploadAttachment(att, entityType, id, ISSUES_FOLDER);
                issue.addAttachment(attachment);
            }
        }
        return issueMapper.toSimpleDto(issueRepository.save(issue));
    }

    private void partialUpdateAnIssue(Map<String, Object> updates, Issue issue) {
        updates.forEach((key, value) -> {
            switch (key) {
                case "title":
                    issue.setTitle((String) value);
                    break;
                case "description":
                    issue.setDescription((String) value);
                    break;
                case "type":
                    issue.setType(IssueType.valueOf((String) value));
                    break;
                case "status":
                    issue.setStatus(IssueStatus.valueOf((String) value));
                    break;
                case "assignedToId":
                    Long assigneeId = ((Number) value).longValue();
                    Employee assignee = employeeRepository.findByIdAndOrganizationId(assigneeId, TenantContext.getCurrentOrgId())
                            .orElseThrow(() -> new ResourceNotFoundException("Assignee (employee) with ID " + assigneeId + " was not found in this organization"));
                    issue.setAssignedTo(assignee);
                    break;
            }
        });
    }

    @Transactional
    public void deleteAnIssue(Long id) {
        if(!issueRepository.existsByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())) {
            throw new ResourceNotFoundException("Issue with ID " + id + " was not found in this organization");
        }

        attachmentService.deleteAllAttachments("ISSUE", id);
        issueRepository.deleteByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId());
    }

}
