package org.tornotron.echno_backend.task;

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
import org.tornotron.echno_backend.category.Category;
import org.tornotron.echno_backend.category.CategoryRepository;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.task.dto.TaskCreationDto;
import org.tornotron.echno_backend.task.mapper.TaskMapper;

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
 * Who a task is recorded as having been created by.
 *
 * <p>Lower stakes than the issue and comment cases, because these endpoints are gated on the
 * system-admin and project-manager roles: only somebody already trusted to manage tasks could
 * misattribute one. It is the same defect all the same, and the field bought nothing. Assigning
 * work to somebody else is what {@code assigneeIds} is for and is untouched.
 *
 * <p>The JSON part a deployed client sends is replayed verbatim, {@code creatorId} and all. On the
 * old code the stored creator is employee 99.
 */
@ExtendWith(MockitoExtension.class)
class TaskCreatorAttributionTest {

    private static final Long ORG_ID = 100L;
    private static final Long CALLER_EMPLOYEE_ID = 7L;
    private static final Long COLLEAGUE_EMPLOYEE_ID = 99L;

    /** Exactly what echno-core's task create serializer puts in the multipart data part today. */
    private static final String DEPLOYED_CLIENT_PAYLOAD = """
            {"title":"Pour foundation slab, block A","status":"upcoming","progress":0.0,
             "projectId":42,"categoryId":3,"creatorId":99}
            """;

    private static ValidatorFactory factory;

    @Mock private TaskRepository taskRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private CurrentEmployeeService currentEmployeeService;
    @Mock private TaskMapper taskMapper;

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    private TaskService service;

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        service = new TaskService(taskRepository, employeeRepository, projectRepository,
                categoryRepository, attachmentService, currentEmployeeService, taskMapper,
                new PayloadValidator(factory.getValidator()));
        TenantContext.setCurrentOrgId(ORG_ID);

        Organization organization = new Organization();
        organization.setId(ORG_ID);
        Project project = new Project();
        project.setOrganization(organization);
        lenient().when(projectRepository.findByIdAndOrganization_Id(anyLong(), anyLong()))
                .thenReturn(Optional.of(project));
        lenient().when(categoryRepository.findByIdAndOrganization_Id(anyLong(), anyLong()))
                .thenReturn(Optional.of(new Category()));
        lenient().when(taskRepository.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));
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

    private TaskCreationDto deployedClientPayload() throws Exception {
        return objectMapper.readValue(DEPLOYED_CLIENT_PAYLOAD, TaskCreationDto.class);
    }

    private Employee employee(Long id) {
        Employee employee = new Employee();
        employee.setId(id);
        return employee;
    }

    @Test
    void aCreatorIdFromAnOlderClientIsAcceptedRatherThanRefused() throws Exception {
        TaskCreationDto dto = deployedClientPayload();

        assertThat(dto.getProjectId()).isEqualTo(42L);
        assertThat(dto.getCategoryId()).isEqualTo(3L);
    }

    @Test
    void theTaskIsCreatedByTheCallerNotByTheIdTheCallerSent() throws Exception {
        when(currentEmployeeService.requireCurrentEmployee(anyString()))
                .thenReturn(employee(CALLER_EMPLOYEE_ID));

        service.addTask(deployedClientPayload(), null);

        ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().getCreator().getId())
                .isEqualTo(CALLER_EMPLOYEE_ID)
                .isNotEqualTo(COLLEAGUE_EMPLOYEE_ID);
    }

    @Test
    void aCallerWithNoEmployeeRecordCannotCreateATask() throws Exception {
        when(currentEmployeeService.requireCurrentEmployee(anyString()))
                .thenThrow(new AccessDeniedException("no employee record"));

        TaskCreationDto dto = deployedClientPayload();
        assertThatThrownBy(() -> service.addTask(dto, null))
                .isInstanceOf(AccessDeniedException.class);

        verify(taskRepository, never()).save(any());
    }
}
