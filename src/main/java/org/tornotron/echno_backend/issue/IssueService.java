package org.tornotron.echno_backend.issue;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.IssueDtoConvertor;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.issue.dto.IssueCreationDto;
import org.tornotron.echno_backend.issue.dto.IssueDto;
import org.tornotron.echno_backend.issue.dto.IssueSimpleDto;
import org.tornotron.echno_backend.issue.enums.IssueStatus;
import org.tornotron.echno_backend.issue.enums.IssueType;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.task.TaskRepository;

import java.util.Map;
import java.util.Objects;

@Service
public class IssueService {

    private final IssueRepository issueRepository;
    private final TaskRepository taskRepository;

    public IssueService(IssueRepository issueRepository, TaskRepository taskRepository) {
        this.issueRepository = issueRepository;
        this.taskRepository = taskRepository;
    }

    @Transactional
    public IssueSimpleDto addIssue(IssueCreationDto issueCreationDto) {
        Task task = taskRepository.findById(issueCreationDto.getTaskId())
                .orElseThrow(() -> new ResourceNotFoundException("Task not found with id: " + issueCreationDto.getTaskId()));
        Issue issue = new Issue();
        issue.setTitle(issueCreationDto.getTitle());
        issue.setDescription(issueCreationDto.getDescription());
        issue.setType(IssueType.valueOf(issueCreationDto.getType()));
        issue.setStatus(IssueStatus.valueOf(issueCreationDto.getStatus()));
        issue.setCreator(issueCreationDto.getCreator());
        issue.setCreator(issueCreationDto.getCreator());
        issue.setTask(task);
        return IssueDtoConvertor.convertIssueToSimpleDto(issueRepository.save(issue));
    }

    @Transactional(readOnly = true)
    public Page<IssueDto> getAllIssues(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo,pageSize, Sort.by(Sort.Direction.ASC, "id"));
        return issueRepository.findAll(pageable)
                .map(IssueDtoConvertor::convertIssueToDto);
    }

    public IssueSimpleDto partialUpdateAnIssue(Map<String, Object> updates, Long id) {
        Issue issue = issueRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Issue not found with id: "+id));
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
            }
        });
        return IssueDtoConvertor.convertIssueToSimpleDto(issueRepository.save(issue));
    }

}
