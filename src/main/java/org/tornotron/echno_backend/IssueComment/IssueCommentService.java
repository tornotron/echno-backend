package org.tornotron.echno_backend.IssueComment;

import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentCreationDto;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.issue.IssueRepository;

@Service
public class IssueCommentService {

    private final IssueCommentRepository issueCommentRepository;
    private final IssueRepository issueRepository;

    public IssueCommentService(IssueCommentRepository issueCommentRepository, IssueRepository issueRepository) {
        this.issueCommentRepository = issueCommentRepository;
        this.issueRepository = issueRepository;
    }

    public void addIssueComment(IssueCommentCreationDto issueCommentCreationDto) {
        IssueComment issueComment = new IssueComment();
        issueComment.setComment(issueCommentCreationDto.getComment());
        if(issueCommentCreationDto.getIssueId() != null) {
            issueComment.setIssue(issueRepository.findById(issueCommentCreationDto.getIssueId())
                    .orElseThrow(() -> new ResourceNotFoundException("Issue not found with id: " + issueCommentCreationDto.getIssueId())));
        }
        issueCommentRepository.save(issueComment);
    }
}
