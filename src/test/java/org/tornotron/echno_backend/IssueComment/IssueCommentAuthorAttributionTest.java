package org.tornotron.echno_backend.IssueComment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.IssueComment.dto.IssueCommentCreationDto;
import org.tornotron.echno_backend.IssueComment.mapper.IssueCommentMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.issue.Issue;
import org.tornotron.echno_backend.issue.IssueRepository;
import org.tornotron.echno_backend.organization.Organization;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who a comment is recorded as having been written by.
 *
 * <p>It used to be an {@code authorId} on the payload. The endpoint is open to every member of
 * the tenant and the only check made on that id was that it named some employee of the tenant, so
 * any member could post a comment in a colleague's name. A comment on an issue is read as its
 * author's own statement, and once the compliance module reads issues, a forged one feeds it.
 *
 * <p>The author now comes from the session. The payload a deployed client sends is replayed here
 * verbatim, {@code authorId} and all, so what these pin is both halves of the change: the key is
 * accepted rather than refused, and the comment is stored under the caller rather than under the
 * id the key carried. On the old code the stored author is 99.
 */
@ExtendWith(MockitoExtension.class)
class IssueCommentAuthorAttributionTest {

    private static final Long ORG_ID = 100L;
    private static final Long CALLER_EMPLOYEE_ID = 7L;
    private static final Long COLLEAGUE_EMPLOYEE_ID = 99L;
    private static final Long ISSUE_ID = 11L;

    /** Exactly what echno-core's {@code createIssueCommentToJson} puts on the wire today. */
    private static final String DEPLOYED_CLIENT_PAYLOAD =
            "{\"issueId\":11,\"comment\":\"Rebar spacing on the east face still needs checking.\",\"authorId\":99}";

    @Mock private IssueCommentRepository issueCommentRepository;
    @Mock private IssueRepository issueRepository;
    @Mock private CurrentEmployeeService currentEmployeeService;
    @Mock private IssueCommentMapper issueCommentMapper;

    /** The mapper Spring Boot's Jackson auto-configuration builds, which is what binds the body. */
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    @BeforeEach
    void setTenant() {
        TenantContext.setCurrentOrgId(ORG_ID);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private IssueCommentService service() {
        return new IssueCommentService(issueCommentRepository, issueRepository,
                currentEmployeeService, issueCommentMapper);
    }

    private Employee employee(Long id) {
        Employee employee = new Employee();
        employee.setId(id);
        return employee;
    }

    private void issueExists() {
        Organization organization = new Organization();
        organization.setId(ORG_ID);
        Issue issue = new Issue();
        issue.setId(ISSUE_ID);
        issue.setOrganization(organization);
        lenient().when(issueRepository.findByIdAndOrganization_Id(anyLong(), anyLong()))
                .thenReturn(Optional.of(issue));
    }

    private IssueCommentCreationDto deployedClientPayload() throws Exception {
        return objectMapper.readValue(DEPLOYED_CLIENT_PAYLOAD, IssueCommentCreationDto.class);
    }

    @Test
    void anAuthorIdFromAnOlderClientIsAcceptedRatherThanRefused() throws Exception {
        IssueCommentCreationDto dto = deployedClientPayload();

        assertThat(dto.getIssueId()).isEqualTo(ISSUE_ID);
        assertThat(dto.getComment()).startsWith("Rebar spacing");
    }

    @Test
    void theCommentIsStoredUnderTheCallerNotUnderTheIdTheCallerSent() throws Exception {
        issueExists();
        when(currentEmployeeService.requireCurrentEmployee(anyString()))
                .thenReturn(employee(CALLER_EMPLOYEE_ID));
        when(issueCommentRepository.save(any(IssueComment.class))).thenAnswer(call -> call.getArgument(0));

        service().addIssueComment(deployedClientPayload());

        ArgumentCaptor<IssueComment> saved = ArgumentCaptor.forClass(IssueComment.class);
        verify(issueCommentRepository).save(saved.capture());
        assertThat(saved.getValue().getAuthorId())
                .isEqualTo(CALLER_EMPLOYEE_ID)
                .isNotEqualTo(COLLEAGUE_EMPLOYEE_ID);
    }

    @Test
    void aCallerWithNoEmployeeRecordCannotCommentAtAll() throws Exception {
        when(currentEmployeeService.requireCurrentEmployee(anyString()))
                .thenThrow(new AccessDeniedException("no employee record"));

        IssueCommentCreationDto dto = deployedClientPayload();
        assertThatThrownBy(() -> service().addIssueComment(dto))
                .isInstanceOf(AccessDeniedException.class);

        verify(issueCommentRepository, never()).save(any());
    }
}
