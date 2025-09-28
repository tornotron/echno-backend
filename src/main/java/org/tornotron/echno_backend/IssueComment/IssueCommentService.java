package org.tornotron.echno_backend.IssueComment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.IssueCommentDtoConvertor;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentCreationDto;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentDto;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentSimpleDto;
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

    @Transactional
    public IssueCommentSimpleDto addIssueComment(IssueCommentCreationDto issueCommentCreationDto) {
        IssueComment issueComment = new IssueComment();
        issueComment.setAuthor(issueCommentCreationDto.getAuthor());
        issueComment.setComment(issueCommentCreationDto.getComment());
        if(issueCommentCreationDto.getIssueId() != null) {
            issueComment.setIssue(issueRepository.findById(issueCommentCreationDto.getIssueId())
                    .orElseThrow(() -> new ResourceNotFoundException("Issue not found with id: " + issueCommentCreationDto.getIssueId())));
        }
        return IssueCommentDtoConvertor.convertIssueCommentToSimpleDto(issueCommentRepository.save(issueComment));
    }

    @Transactional(readOnly = true)
    public Page<IssueCommentDto> getAllIssueComments(int pageNo,int pageSize) {
        Pageable pageable = PageRequest.of(pageNo,pageSize, Sort.by(Sort.Direction.ASC,"id"));
        return issueCommentRepository.findAll(pageable)
                .map(IssueCommentDtoConvertor::convertIssueCommentToDto);
    }

    @Transactional
    public void deleteAnIssueComment(Long id) {
        if(!issueCommentRepository.existsById(id)){
            throw new ResourceNotFoundException("IsseComment not found with id: " + id);
        } else {
            issueCommentRepository.deleteById(id);
        }
    }
}
