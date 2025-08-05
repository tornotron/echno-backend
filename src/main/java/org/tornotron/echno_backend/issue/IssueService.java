package org.tornotron.echno_backend.issue;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.issue.dto.IssueCreationDto;
import org.tornotron.echno_backend.issue.enums.IssueStatus;
import org.tornotron.echno_backend.issue.enums.IssueType;

@Service
@Transactional
public class IssueService {

    private final IssueRepository issueRepository;

    public IssueService(IssueRepository issueRepository) {
        this.issueRepository = issueRepository;
    }

    public void addIssue(IssueCreationDto issueCreationDto) {
        Issue issue = new Issue();
        issue.setTitle(issueCreationDto.getTitle());
        issue.setDescription(issueCreationDto.getDescription());
        issue.setType(IssueType.valueOf(issueCreationDto.getType()));
        issue.setStatus(IssueStatus.valueOf(issueCreationDto.getStatus()));
        issue.setCreator(issueCreationDto.getCreator());
        issue.setCreator(issueCreationDto.getCreator());
        issueRepository.save(issue);
    }
}
