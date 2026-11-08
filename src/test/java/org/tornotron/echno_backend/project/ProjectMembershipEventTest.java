package org.tornotron.echno_backend.project;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.common.history.StatusTransitionRepository;
import org.tornotron.echno_backend.common.history.mapper.StatusTransitionMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.finance.ledger.repositories.CustomerRepository;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.mapper.ProjectMapper;
import org.tornotron.echno_backend.user.UserContextService;

/** Joining or leaving a project says so, and a refused change says nothing. */
class ProjectMembershipEventTest {

    private static final long ORG = 7L;
    private static final long PROJECT = 100L;
    private static final long EMPLOYEE = 1L;

    private final ProjectRepository repository = mock(ProjectRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ValidatorFactory validators = Validation.buildDefaultValidatorFactory();

    private ProjectService service;
    private Project project;
    private Employee employee;

    @BeforeEach
    void setUp() {
        service = new ProjectService(repository, mock(OrganizationRepository.class), employees,
                mock(AttachmentService.class), mock(ProjectMapper.class), mock(EmployeeMapper.class), events,
                mock(CustomerRepository.class), new PayloadValidator(validators.getValidator()),
                mock(UserContextService.class), mock(StatusTransitionRecorder.class),
                mock(StatusTransitionRepository.class), mock(StatusTransitionMapper.class),
                mock(OrganizationSecurityService.class),
                ProjectAccessStubs.allowingEverything());
        TenantContext.setCurrentOrgId(ORG);
        project = new Project();
        project.setEmployees(new ArrayList<>());
        employee = new Employee();
        employee.setId(EMPLOYEE);
        when(repository.findByIdAndOrganization_Id(PROJECT, ORG)).thenReturn(Optional.of(project));
        when(employees.findByIdAndOrganizationId(EMPLOYEE, ORG)).thenReturn(Optional.of(employee));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        validators.close();
    }

    @Test
    void joiningAProjectSaysSo() {
        service.addEmployeeToProject(PROJECT, EMPLOYEE);

        verify(events).publishEvent(new ProjectMembershipChanged(ORG, EMPLOYEE));
    }

    @Test
    void leavingAProjectSaysSo() {
        project.getEmployees().add(employee);

        service.removeEmployeeFromProject(PROJECT, EMPLOYEE);

        verify(events).publishEvent(new ProjectMembershipChanged(ORG, EMPLOYEE));
    }

    @Test
    void aRefusedChangeSaysNothing() {
        assertThatThrownBy(() -> service.removeEmployeeFromProject(PROJECT, EMPLOYEE))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(events, never()).publishEvent(any(Object.class));
    }
}
