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
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.issue.IssueRepository;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class IssueCommentService {

    private final IssueCommentRepository issueCommentRepository;
    private final IssueRepository issueRepository;
    private final EmployeeRepository employeeRepository;

    public IssueCommentService(IssueCommentRepository issueCommentRepository, IssueRepository issueRepository, EmployeeRepository employeeRepository) {
        this.issueCommentRepository = issueCommentRepository;
        this.issueRepository = issueRepository;
        this.employeeRepository = employeeRepository;
    }

    @Transactional
    public IssueCommentSimpleDto addIssueComment(IssueCommentCreationDto issueCommentCreationDto) {
        IssueComment issueComment = new IssueComment();
        if(!employeeRepository.existsByIdAndOrganization_Id(issueCommentCreationDto.getAuthorId(), TenantContext.getCurrentOrgId())) {
            throw new ResourceNotFoundException("Author (employee) with ID " + issueCommentCreationDto.getAuthorId() + " was not found in this organization");
        }
        issueComment.setAuthorId(issueCommentCreationDto.getAuthorId());
        issueComment.setComment(issueCommentCreationDto.getComment());
        if(issueCommentCreationDto.getIssueId() != null) {
            var issue = issueRepository.findByIdAndOrganization_Id(issueCommentCreationDto.getIssueId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Issue with ID " + issueCommentCreationDto.getIssueId() + " was not found in this organization"));
            issueComment.setIssue(issue);
            issueComment.setOrganization(issue.getOrganization());
        }
        return IssueCommentDtoConvertor.convertIssueCommentToSimpleDto(issueCommentRepository.save(issueComment));
    }

    @Transactional(readOnly = true)
    public Page<IssueCommentDto> getAllIssueComments(int pageNo,int pageSize) {
        Pageable pageable = PageRequest.of(pageNo,pageSize, Sort.by(Sort.Direction.ASC,"id"));
        return issueCommentRepository.findAll(pageable)
                .map(IssueCommentDtoConvertor::convertIssueCommentToDto);
    }

    @Transactional(readOnly = true)
    public IssueCommentDto getAnIssueComment(Long id) {
        IssueComment issueComment = issueCommentRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Issue comment with ID " + id + " was not found in this organization"));
        return IssueCommentDtoConvertor.convertIssueCommentToDto(issueComment);
    }

    @Transactional(readOnly = true)
    public List<IssueCommentDto> getAllIssueCommentsByIssueId(Long issueId) {
        return issueCommentRepository.findAllByIssue_IdAndOrganization_Id(issueId,TenantContext.getCurrentOrgId()).stream()
                .map(IssueCommentDtoConvertor::convertIssueCommentToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteAnIssueComment(Long id) {
        if(!issueCommentRepository.existsByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())){
            throw new ResourceNotFoundException("Issue comment with ID " + id + " was not found in this organization");
        } else {
            issueCommentRepository.deleteByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId());
        }
    }
}
