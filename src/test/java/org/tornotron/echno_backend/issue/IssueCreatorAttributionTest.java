package org.tornotron.echno_backend.issue;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.issue.dto.IssueCreationDto;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who an issue is recorded as having been raised by.
 *
 * <p>It used to be a {@code createdById} on the payload, and the endpoint is open to every member
 * of the tenant, so the only check that id could carry was that it named some employee of the
 * tenant. An issue "raised by" the site engineer is read as their report, and the compliance
 * module reads issues.
 *
 * <p>The creator now comes from the session. The JSON part a deployed client sends is replayed
 * here verbatim, {@code createdById} and all, so these pin both halves: the key is accepted
 * rather than refused, and the issue is stored under the caller. On the old code the stored
 * creator is employee 99.
 */
@ExtendWith(MockitoExtension.class)
class IssueCreatorAttributionTest {

    private static final Long ORG_ID = 100L;
    private static final Long CALLER_EMPLOYEE_ID = 7L;
    private static final Long COLLEAGUE_EMPLOYEE_ID = 99L;

    /** Exactly what echno-core's {@code createIssueToJson} puts in the multipart data part today. */
    private static final String DEPLOYED_CLIENT_PAYLOAD = """
            {"title":"Honeycombing on the block A raft",
             "description":"Voids visible along the north edge of the pour after stripping.",
             "type":"quality","taskId":11,"createdById":99}
            """;

    private static ValidatorFactory factory;

    @Mock private IssueRepository issueRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private IssueMapper issueMapper;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private CurrentEmployeeService currentEmployeeService;

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    private IssueService service;

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        service = new IssueService(issueRepository, taskRepository, attachmentService, issueMapper,
                employeeRepository, currentEmployeeService,
                new PayloadValidator(factory.getValidator()));
        TenantContext.setCurrentOrgId(ORG_ID);

        Organization organization = new Organization();
        organization.setId(ORG_ID);
        Task task = new Task();
        task.setOrganization(organization);
        lenient().when(taskRepository.findByIdAndOrganization_Id(anyLong(), anyLong()))
                .thenReturn(Optional.of(task));
        lenient().when(issueRepository.save(any(Issue.class))).thenAnswer(call -> call.getArgument(0));
        // Employee 99 is a real colleague of this tenant, resolvable by id. That is the whole
        // point: the id the payload carries is not refused for being unknown, it is not consulted.
        lenient().when(employeeRepository.findByIdAndOrganizationId(anyLong(), anyLong()))
                .thenAnswer(call -> Optional.of(employee(call.getArgument(0))));
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

    private IssueCreationDto deployedClientPayload() throws Exception {
        return objectMapper.readValue(DEPLOYED_CLIENT_PAYLOAD, IssueCreationDto.class);
    }

    private Employee employee(Long id) {
        Employee employee = new Employee();
        employee.setId(id);
        return employee;
    }

    @Test
    void aCreatedByIdFromAnOlderClientIsAcceptedRatherThanRefused() throws Exception {
        IssueCreationDto dto = deployedClientPayload();

        assertThat(dto.getTaskId()).isEqualTo(11L);
        assertThat(dto.getType()).isEqualTo("quality");
    }

    @Test
    void theIssueIsRaisedByTheCallerNotByTheIdTheCallerSent() throws Exception {
        when(currentEmployeeService.requireCurrentEmployee(anyString()))
                .thenReturn(employee(CALLER_EMPLOYEE_ID));

        service.addIssue(deployedClientPayload(), null);

        ArgumentCaptor<Issue> saved = ArgumentCaptor.forClass(Issue.class);
        verify(issueRepository).save(saved.capture());
        assertThat(saved.getValue().getCreatedBy().getId())
                .isEqualTo(CALLER_EMPLOYEE_ID)
                .isNotEqualTo(COLLEAGUE_EMPLOYEE_ID);
    }

    @Test
    void aCallerWithNoEmployeeRecordCannotRaiseAnIssue() throws Exception {
        when(currentEmployeeService.requireCurrentEmployee(anyString()))
                .thenThrow(new AccessDeniedException("no employee record"));

        IssueCreationDto dto = deployedClientPayload();
        assertThatThrownBy(() -> service.addIssue(dto, null))
                .isInstanceOf(AccessDeniedException.class);

        verify(issueRepository, never()).save(any());
    }
}
