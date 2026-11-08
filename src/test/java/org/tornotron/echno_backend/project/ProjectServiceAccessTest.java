package org.tornotron.echno_backend.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.common.history.StatusTransitionRepository;
import org.tornotron.echno_backend.common.history.mapper.StatusTransitionMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.finance.ledger.repositories.CustomerRepository;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessGrants;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.dto.ProjectCreationDto;
import org.tornotron.echno_backend.project.mapper.ProjectMapper;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The project service asks the access matrix about each project, not only about the area: a caller
 * whose grants reach one project sees, changes and staffs only that project.
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceAccessTest {

    private static final Long ORG = 100L;
    private static final Long MINE = 10L;
    private static final Long ELSEWHERE = 20L;
    private static final Long NEW = 30L;
    private static final Long EMPLOYEE = 7L;

    @Mock private ProjectRepository repository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private ProjectMapper projectMapper;
    @Mock private EmployeeMapper employeeMapper;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private CustomerRepository customerRepository;
    @Mock private PayloadValidator payloadValidator;
    @Mock private UserContextService userContextService;
    @Mock private StatusTransitionRecorder statusTransitionRecorder;
    @Mock private StatusTransitionRepository statusTransitionRepository;
    @Mock private StatusTransitionMapper statusTransitionMapper;
    @Mock private CurrentEmployeeService currentEmployee;

    private final OneProjectAccess access = new OneProjectAccess();
    private ProjectService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        Employee caller = new Employee();
        caller.setId(EMPLOYEE);
        lenient().when(currentEmployee.currentEmployee()).thenReturn(Optional.of(caller));
        service = new ProjectService(repository, organizationRepository, employeeRepository, attachmentService,
                projectMapper, employeeMapper, eventPublisher, customerRepository, payloadValidator,
                userContextService, statusTransitionRecorder, statusTransitionRepository, statusTransitionMapper,
                new ProjectAccess(access, currentEmployee));
        lenient().when(repository.findByIdAndOrganization_Id(MINE, ORG)).thenReturn(Optional.of(project(MINE)));
        lenient().when(repository.findByIdAndOrganization_Id(ELSEWHERE, ORG)).thenReturn(Optional.of(project(ELSEWHERE)));
        lenient().when(employeeRepository.findByIdAndOrganizationId(EMPLOYEE, ORG)).thenReturn(Optional.of(caller));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aProjectTheCallerMayNotSeeReadsAsMissing() {
        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> service.getAProject(ELSEWHERE));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.getStatusHistory(ELSEWHERE, 0, 20));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.getEmployeesByProjectId(ELSEWHERE));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.partialUpdateAProject(Map.of("description", "x"), ELSEWHERE, null, "PROJECT"));
        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> service.deleteAProject(ELSEWHERE));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.requireAccess(ELSEWHERE, AccessPermission.READ));
    }

    @Test
    void aProjectTheCallerMaySeeButNotChangeIsRefusedAndKept() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() -> service.deleteAProject(MINE));
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> service.partialUpdateAProject(Map.of("description", "x"), MINE, null, "PROJECT"));
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> service.removeEmployeeFromProject(MINE, EMPLOYEE));
        // The site structure takes the project's facts, through this check.
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> service.requireAccess(MINE, AccessPermission.EDIT));
        verify(repository, never()).deleteByIdAndOrganization_Id(anyLong(), anyLong());
        verify(repository, never()).save(any());
    }

    @Test
    void aProjectTheCallerMayStaffTakesTheNewMember() {
        when(repository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        service.addEmployeeToProject(MINE, EMPLOYEE);

        verify(repository).save(any(Project.class));
    }

    @Test
    void aProjectCannotBeCreatedOutsideWhatTheCallerMayCreate() {
        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(organization()));
        when(repository.save(any(Project.class))).thenAnswer(inv -> {
            Project saved = inv.getArgument(0);
            saved.setId(NEW);
            return saved;
        });
        ProjectCreationDto dto = new ProjectCreationDto();
        dto.setProjectName("Riverside");

        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() -> service.addProject(dto, List.of()));
        verify(statusTransitionRecorder, never()).recordCreation(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void theListsAreNarrowedInTheQuery() {
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        when(repository.findAll(any(Specification.class))).thenReturn(List.of());

        service.getAllProjects(0, 10);
        service.getProjectsPaginated(0, 10, "river");
        service.getProjectsSummaryPaginated(0, 10, null);
        service.getProjectsByEmployeeId(EMPLOYEE);

        verify(repository, never()).findAll(any(Pageable.class));
        verify(repository, never()).search(any(), any());
        verify(repository, never()).findByEmployees_IdAndOrganization_Id(any(), any());
    }

    @Test
    void aCallerWhoMaySeeEveryProjectGetsTheUnnarrowedLists() {
        access.everything = true;
        when(repository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(repository.search(any(), any())).thenReturn(Page.empty());

        service.getAllProjects(0, 10);
        service.getProjectsPaginated(0, 10, "river");
        service.getProjectsByEmployeeId(EMPLOYEE);

        verify(repository, never()).findAll(any(Specification.class), any(Pageable.class));
        verify(repository, never()).findAll(any(Specification.class));
        verify(repository).findByEmployees_IdAndOrganization_Id(EMPLOYEE, ORG);
    }

    @Test
    void aCallerWhoMaySeeNoProjectIsNotQueriedFor() {
        access.nothing = true;

        assertThat(service.getAllProjects(0, 10)).isEmpty();
        assertThat(service.getProjectsPaginated(0, 10, null)).isEmpty();
        assertThat(service.getProjectsSummaryPaginated(0, 10, null)).isEmpty();
        assertThat(service.getProjectsByEmployeeId(EMPLOYEE)).isEmpty();
        verify(repository, never()).findAll(any(Pageable.class));
        verify(repository, never()).search(any(), any());
        verify(repository, never()).findAll(any(Specification.class), any(Pageable.class));
        verify(repository, never()).findByEmployees_IdAndOrganization_Id(any(), any());
    }

    private Organization organization() {
        Organization organization = new Organization();
        organization.setId(ORG);
        return organization;
    }

    private Project project(Long id) {
        Project project = new Project();
        project.setId(id);
        project.setProjectName("Project " + id);
        project.setOrganization(organization());
        project.setEmployees(new ArrayList<>());
        return project;
    }

    /** Grants read, and create of what sits on the project (a new team member), on one project only. */
    private static final class OneProjectAccess implements AccessService {

        private static final Set<String> HELD = Set.of(AccessPermission.READ, AccessPermission.CREATE);

        boolean everything;
        boolean nothing;

        @Override
        public boolean isActiveMember(AccessSubject subject) {
            return true;
        }

        @Override
        public boolean check(AccessSubject subject, AccessPermission permission) {
            return !nothing && HELD.contains(permission.action());
        }

        @Override
        public boolean check(AccessSubject subject, AccessPermission permission, RecordFacts record) {
            return filter(subject, permission).admits(record);
        }

        @Override
        public AccessFilter filter(AccessSubject subject, AccessPermission permission) {
            if (nothing || !HELD.contains(permission.action())) {
                return AccessFilter.none(subject.organizationId());
            }
            if (everything) {
                return AccessFilter.all(subject.organizationId());
            }
            return new AccessFilter(subject.organizationId(), false, Set.of(MINE), null, null);
        }

        @Override
        public AccessGrants grantsFor(AccessSubject subject) {
            return AccessGrants.none();
        }
    }
}
