package org.tornotron.echno_backend.IssueComment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.IssueComment.mapper.IssueCommentMapper;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentCreationDto;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentDto;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentSimpleDto;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.issue.IssueRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class IssueCommentService {

    private final IssueCommentRepository issueCommentRepository;
    private final IssueRepository issueRepository;
    private final CurrentEmployeeService currentEmployeeService;
    private final IssueCommentMapper issueCommentMapper;

    public IssueCommentService(IssueCommentRepository issueCommentRepository, IssueRepository issueRepository, CurrentEmployeeService currentEmployeeService, IssueCommentMapper issueCommentMapper) {
        this.issueCommentRepository = issueCommentRepository;
        this.issueRepository = issueRepository;
        this.currentEmployeeService = currentEmployeeService;
        this.issueCommentMapper = issueCommentMapper;
    }

    /**
     * Posts a comment on an issue, attributed to the signed-in caller.
     *
     * <p>The author is resolved from the session rather than read off the payload. An
     * {@code authorId} the caller sends is not an author, it is a claim: the endpoint's guard is
     * tenant membership, so the only check the old code could make was that the id named some
     * employee of the tenant, and every member could therefore leave a comment in a colleague's
     * name. A comment is read as its author's own statement, so the field it is stored under has
     * to come from the session that wrote it.
     *
     * @param issueCommentCreationDto The comment text and the issue it belongs to.
     * @return The saved comment.
     * @throws org.springframework.security.access.AccessDeniedException if the caller has no
     *     employee record in this organization, so the comment would name nobody.
     * @throws ResourceNotFoundException if the issue is not in this organization.
     */
    @Transactional
    public IssueCommentSimpleDto addIssueComment(IssueCommentCreationDto issueCommentCreationDto) {
        IssueComment issueComment = new IssueComment();
        issueComment.setAuthorId(currentEmployeeService.requireCurrentEmployee("comment on an issue").getId());
        issueComment.setComment(issueCommentCreationDto.getComment());
        if(issueCommentCreationDto.getIssueId() != null) {
            var issue = issueRepository.findByIdAndOrganization_Id(issueCommentCreationDto.getIssueId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Issue with ID " + issueCommentCreationDto.getIssueId() + " was not found in this organization"));
            issueComment.setIssue(issue);
            issueComment.setOrganization(issue.getOrganization());
        }
        return issueCommentMapper.toSimpleDto(issueCommentRepository.save(issueComment));
    }

    @Transactional(readOnly = true)
    public Page<IssueCommentDto> getAllIssueComments(int pageNo,int pageSize) {
        Pageable pageable = PageRequest.of(pageNo,pageSize, Sort.by(Sort.Direction.ASC,"id"));
        return issueCommentRepository.findAll(pageable)
                .map(issueCommentMapper::toDto);
    }

    @Transactional(readOnly = true)
    public IssueCommentDto getAnIssueComment(Long id) {
        IssueComment issueComment = issueCommentRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Issue comment with ID " + id + " was not found in this organization"));
        return issueCommentMapper.toDto(issueComment);
    }

    @Transactional(readOnly = true)
    public List<IssueCommentDto> getAllIssueCommentsByIssueId(Long issueId) {
        return issueCommentRepository.findAllByIssue_IdAndOrganization_Id(issueId,TenantContext.getCurrentOrgId()).stream()
                .map(issueCommentMapper::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Corrects the text of a comment the caller wrote, and marks it edited.
     *
     * <p><b>The author, and nobody else.</b> Not a system admin, not a project manager. The two of
     * them may already delete a comment, which is a visible act that leaves the thread obviously
     * shorter; changing the words inside somebody else's name is not visible at all, and a reader
     * has no way to tell it from what the author wrote. That is a worse artifact than the gap this
     * closes.
     *
     * <p><b>The check is here rather than in a {@code @PreAuthorize} expression</b>, and that is
     * deliberate. A guard that took the comment id would have to load the row to find its author,
     * and the service then loads it again to write to it: two lookups that can drift apart, which
     * is the shape a run of defects in this codebase has come from. Here the row the author is
     * read from is the row the text is written to, so they cannot disagree.
     * {@code ChatService.editMessage} settles the same question the same way, and the endpoint
     * carries {@code isMemberOfCurrentTenant} exactly as the chat one does.
     *
     * <p>A comment in another organization is not found rather than refused. The finder is scoped
     * to the tenant, so a foreign id yields the 404 a nonexistent id yields, and a caller cannot
     * use the difference between 403 and 404 to learn that a comment exists somewhere they cannot
     * see.
     *
     * <p>There is no time window. A five-minute edit window is a rule about how long a mistake
     * stays fixable, and nobody has asked for one; the marker is what keeps the record honest, not
     * the deadline. Adding one later changes no stored state.
     *
     * @param id The comment to correct.
     * @param comment The replacement text.
     * @return The updated comment, carrying the edited marker.
     * @throws org.springframework.security.access.AccessDeniedException if the caller has no
     *     employee record in this organization, or is not the comment's author.
     * @throws ResourceNotFoundException if no such comment is in this organization.
     */
    @Transactional
    public IssueCommentDto updateIssueComment(Long id, String comment) {
        Long callerEmployeeId = currentEmployeeService.requireCurrentEmployee("edit a comment").getId();

        IssueComment issueComment = issueCommentRepository
                .findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Issue comment with ID " + id + " was not found in this organization"));

        if (!callerEmployeeId.equals(issueComment.getAuthorId())) {
            throw new AccessDeniedException("Only the author can edit this comment");
        }

        issueComment.setComment(comment);
        issueComment.setEdited(true);
        issueComment.setEditedAt(LocalDateTime.now());

        return issueCommentMapper.toDto(issueCommentRepository.save(issueComment));
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
