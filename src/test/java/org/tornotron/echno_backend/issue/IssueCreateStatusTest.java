package org.tornotron.echno_backend.issue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.issue.dto.IssueCreationDto;
import org.tornotron.echno_backend.issue.enums.IssueStatus;
import org.tornotron.echno_backend.issue.mapper.IssueMapper;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.task.TaskRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Raising an issue is open to every member of the tenant; changing one is not, and wants
 * {@code system-admin} or {@code project-manager}. A create that copied the payload's status
 * therefore let a member land an issue straight in {@code resolved} or {@code closed}, which is
 * the move that gate exists to withhold. These pin that create takes only {@code open}.
 *
 * <p>Plain Mockito with a real validator and no Spring context.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IssueCreateStatusTest {

    private static ValidatorFactory factory;

    @Mock
    private IssueRepository issueRepository;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private IssueMapper issueMapper;
    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private CurrentEmployeeService currentEmployeeService;

    private IssueService service;

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        service = new IssueService(issueRepository, taskRepository, attachmentService,
                issueMapper, employeeRepository, currentEmployeeService,
                new PayloadValidator(validator));

        TenantContext.setCurrentOrgId(1L);
        Organization organization = new Organization();
        organization.setId(1L);
        Task task = new Task();
        task.setOrganization(organization);
        when(taskRepository.findByIdAndOrganization_Id(anyLong(), anyLong()))
                .thenReturn(Optional.of(task));
        when(currentEmployeeService.requireCurrentEmployee(anyString())).thenReturn(new Employee());
        when(issueRepository.save(any(Issue.class))).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    private IssueCreationDto validDto() {
        IssueCreationDto dto = new IssueCreationDto();
        dto.setTitle("Honeycombing on the block A raft");
        dto.setDescription("Voids visible along the north edge of the pour after stripping.");
        dto.setType("quality");
        dto.setTaskId(11L);
        return dto;
    }

    @Test
    void addIssue_refusesAnIssueRaisedAlreadyResolved() {
        IssueCreationDto dto = validDto();
        dto.setStatus(IssueStatus.resolved);

        assertThatThrownBy(() -> service.addIssue(dto, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("cannot be created as resolved");

        verify(issueRepository, never()).save(any());
    }

    @Test
    void addIssue_refusesAnIssueRaisedAlreadyClosed() {
        IssueCreationDto dto = validDto();
        dto.setStatus(IssueStatus.closed);

        assertThatThrownBy(() -> service.addIssue(dto, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("cannot be created as closed");

        verify(issueRepository, never()).save(any());
    }

    @Test
    void addIssue_refusesEveryStartingStateButOpen() {
        // The patch endpoint gates every transition, not only the terminal ones, so any other
        // starting value is a state the same caller could not have asked to move the issue into.
        for (IssueStatus status : IssueStatus.values()) {
            if (status == IssueStatus.open) {
                continue;
            }
            IssueCreationDto dto = validDto();
            dto.setStatus(status);

            assertThatThrownBy(() -> service.addIssue(dto, null))
                    .isInstanceOf(InvalidRequestException.class);
        }

        verify(issueRepository, never()).save(any());
    }

    @Test
    void addIssue_raisesTheIssueOpenWhenThePayloadNamesNoStatus() {
        IssueCreationDto dto = validDto();
        dto.setStatus(null);

        service.addIssue(dto, null);

        assertThat(savedIssue().getStatus()).isEqualTo(IssueStatus.open);
    }

    @Test
    void addIssue_raisesTheIssueOpenWhenThePayloadAsksForOpen() {
        IssueCreationDto dto = validDto();
        dto.setStatus(IssueStatus.open);

        service.addIssue(dto, null);

        assertThat(savedIssue().getStatus()).isEqualTo(IssueStatus.open);
    }

    private Issue savedIssue() {
        ArgumentCaptor<Issue> captor = ArgumentCaptor.forClass(Issue.class);
        verify(issueRepository).save(captor.capture());
        return captor.getValue();
    }
}
