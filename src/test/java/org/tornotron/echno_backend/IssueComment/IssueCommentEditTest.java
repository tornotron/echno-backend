package org.tornotron.echno_backend.IssueComment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentDto;
import org.tornotron.echno_backend.IssueComment.mapper.IssueCommentMapper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.issue.IssueRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who may change a comment after it is posted, and what the record says afterwards.
 *
 * <p>A comment could be posted and deleted and nothing else, so fixing a typo meant deleting and
 * reposting, which moved the comment to the bottom of the thread and restamped its time. See #676.
 *
 * <p>Three things are pinned, and each answers one of the questions that made this more than a
 * controller method. Only the author may edit, so nobody rewrites words that appear under somebody
 * else's name. The edit is marked, so a reader of the QA trail can tell a corrected comment from an
 * original one. And a comment in another organization is not found rather than refused, so the
 * choice between 404 and 403 does not tell a caller that a comment exists somewhere they cannot
 * see.
 *
 * <p>The authorship check lives in the service rather than in a {@code @PreAuthorize} expression,
 * which is why these are service tests rather than a web slice. A guard would have to load the row
 * to find its author and the service would load it again to write to it; here it is one load, so
 * there is no second lookup to drift. That also makes these tests non-vacuous in the way a mocked
 * {@code @orgSecurity} in a web slice is not: the decision under test is the real code path, not a
 * stub of it.
 */
@ExtendWith(MockitoExtension.class)
class IssueCommentEditTest {

    private static final Long ORG_ID = 100L;
    private static final Long AUTHOR_EMPLOYEE_ID = 7L;
    private static final Long COLLEAGUE_EMPLOYEE_ID = 99L;
    private static final Long COMMENT_ID = 42L;

    private static final String ORIGINAL = "Rebar spacing on the east face still needs cheking.";
    private static final String CORRECTED = "Rebar spacing on the east face still needs checking.";

    @Mock private IssueCommentRepository issueCommentRepository;
    @Mock private IssueRepository issueRepository;
    @Mock private CurrentEmployeeService currentEmployeeService;
    @Mock private IssueCommentMapper issueCommentMapper;

    private IssueCommentService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG_ID);
        service = new IssueCommentService(
                issueCommentRepository, issueRepository, currentEmployeeService, issueCommentMapper);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void callerIsEmployee(Long employeeId) {
        Employee caller = new Employee();
        caller.setId(employeeId);
        when(currentEmployeeService.requireCurrentEmployee(any())).thenReturn(caller);
    }

    private IssueComment existingComment() {
        IssueComment comment = new IssueComment();
        comment.setId(COMMENT_ID);
        comment.setAuthorId(AUTHOR_EMPLOYEE_ID);
        comment.setComment(ORIGINAL);
        return comment;
    }

    @Test
    void theAuthorMayCorrectTheirOwnComment() {
        callerIsEmployee(AUTHOR_EMPLOYEE_ID);
        when(issueCommentRepository.findByIdAndOrganization_Id(COMMENT_ID, ORG_ID))
                .thenReturn(Optional.of(existingComment()));
        when(issueCommentRepository.save(any(IssueComment.class))).thenAnswer(call -> call.getArgument(0));
        lenient().when(issueCommentMapper.toDto(any(IssueComment.class))).thenReturn(new IssueCommentDto());

        service.updateIssueComment(COMMENT_ID, CORRECTED);

        ArgumentCaptor<IssueComment> saved = ArgumentCaptor.forClass(IssueComment.class);
        verify(issueCommentRepository).save(saved.capture());
        assertThat(saved.getValue().getComment()).isEqualTo(CORRECTED);
    }

    /**
     * The marker is the reason this is not a plain setter. A comment that changes silently is a
     * worse record than one that cannot change, because a reader of the QA trail has no way to
     * tell it apart from what was originally written.
     */
    @Test
    void anEditIsMarkedAndTimestamped() {
        callerIsEmployee(AUTHOR_EMPLOYEE_ID);
        when(issueCommentRepository.findByIdAndOrganization_Id(COMMENT_ID, ORG_ID))
                .thenReturn(Optional.of(existingComment()));
        when(issueCommentRepository.save(any(IssueComment.class))).thenAnswer(call -> call.getArgument(0));
        lenient().when(issueCommentMapper.toDto(any(IssueComment.class))).thenReturn(new IssueCommentDto());

        service.updateIssueComment(COMMENT_ID, CORRECTED);

        ArgumentCaptor<IssueComment> saved = ArgumentCaptor.forClass(IssueComment.class);
        verify(issueCommentRepository).save(saved.capture());
        assertThat(saved.getValue().isEdited()).isTrue();
        assertThat(saved.getValue().getEditedAt()).isNotNull();
    }

    /** A comment nobody has touched must not claim to have been edited. */
    @Test
    void aFreshCommentIsNotMarkedEdited() {
        IssueComment fresh = new IssueComment();

        assertThat(fresh.isEdited()).isFalse();
        assertThat(fresh.getEditedAt()).isNull();
    }

    /**
     * A colleague, including one who may delete the comment. Deleting is a visible act that leaves
     * the thread obviously shorter; rewriting the words under somebody else's name is not visible
     * at all.
     */
    @Test
    void aColleagueMayNotEditSomebodyElsesComment() {
        callerIsEmployee(COLLEAGUE_EMPLOYEE_ID);
        when(issueCommentRepository.findByIdAndOrganization_Id(COMMENT_ID, ORG_ID))
                .thenReturn(Optional.of(existingComment()));

        assertThatThrownBy(() -> service.updateIssueComment(COMMENT_ID, CORRECTED))
                .isInstanceOf(AccessDeniedException.class);

        verify(issueCommentRepository, never()).save(any());
    }

    /**
     * The refusal has to land before the write. A refusal after the save would leave the corrected
     * text behind and only report that it should not have been allowed.
     */
    @Test
    void aRefusedEditLeavesTheOriginalTextAlone() {
        callerIsEmployee(COLLEAGUE_EMPLOYEE_ID);
        IssueComment existing = existingComment();
        when(issueCommentRepository.findByIdAndOrganization_Id(COMMENT_ID, ORG_ID))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.updateIssueComment(COMMENT_ID, CORRECTED))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(existing.getComment()).isEqualTo(ORIGINAL);
        assertThat(existing.isEdited()).isFalse();
    }

    /**
     * The finder is tenant-scoped, so a comment belonging to another organization is absent rather
     * than forbidden. That is deliberate: a 403 here would confirm the comment exists.
     */
    @Test
    void aCommentInAnotherOrganizationIsNotFoundRatherThanForbidden() {
        callerIsEmployee(AUTHOR_EMPLOYEE_ID);
        when(issueCommentRepository.findByIdAndOrganization_Id(COMMENT_ID, ORG_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateIssueComment(COMMENT_ID, CORRECTED))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(issueCommentRepository, never()).save(any());
    }

    /**
     * The author is resolved through {@code requireCurrentEmployee}, so a caller with no employee
     * record in this organization is refused rather than compared against a null id.
     */
    @Test
    void aCallerWithNoEmployeeRecordIsRefused() {
        when(currentEmployeeService.requireCurrentEmployee(any()))
                .thenThrow(new AccessDeniedException("no employee record"));

        assertThatThrownBy(() -> service.updateIssueComment(COMMENT_ID, CORRECTED))
                .isInstanceOf(AccessDeniedException.class);

        verify(issueCommentRepository, never()).save(any());
    }
}
