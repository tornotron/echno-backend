package org.tornotron.echno_backend.project;

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
import org.springframework.context.ApplicationEventPublisher;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.common.history.StatusTransitionRepository;
import org.tornotron.echno_backend.common.history.mapper.StatusTransitionMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.finance.ledger.repositories.CustomerRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.dto.ProjectCreationDto;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;
import org.tornotron.echno_backend.project.mapper.ProjectMapper;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A project's status has to be answerable after the fact: when it last moved, who moved it, and
 * whether it was created holding a status or patched into it.
 *
 * <p>The question came from a project found approved with no compliance inspections, where it was
 * impossible to tell whether it had been created approved or patched there. Approval is the
 * transition with behaviour behind it, since it draws up the compliance inspections, and until
 * now nothing recorded that it had happened at all. These pin what is recorded, what is
 * deliberately not recorded, and the actor on both.
 *
 * <p>Plain Mockito with a real validator and no Spring context, following the other tests on this
 * service.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectStatusHistoryTest {

    private static final Long ORG_ID = 1L;
    private static final Long PROJECT_ID = 7L;
    private static final Long ACTOR_ID = 31L;
    private static final Long EMPLOYEE_ID = 55L;

    private static ValidatorFactory factory;

    @Mock
    private ProjectRepository repository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private EmployeeMapper employeeMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private UserContextService userContextService;
    @Mock
    private StatusTransitionRecorder statusTransitionRecorder;
    @Mock
    private StatusTransitionRepository statusTransitionRepository;
    @Mock
    private StatusTransitionMapper statusTransitionMapper;

    private ProjectService service;
    private Organization organization;
    private User actor;

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        service = new ProjectService(repository, organizationRepository, employeeRepository,
                attachmentService, projectMapper, employeeMapper, eventPublisher,
                customerRepository, new PayloadValidator(validator), userContextService,
                statusTransitionRecorder, statusTransitionRepository, statusTransitionMapper);

        TenantContext.setCurrentOrgId(ORG_ID);

        organization = new Organization();
        organization.setId(ORG_ID);

        actor = new User();
        actor.setId(ACTOR_ID);
        actor.setName("Anand Rajashekar");

        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(organization));
        when(repository.existsProjectByProjectName(anyString())).thenReturn(false);
        when(repository.save(any(Project.class))).thenAnswer(call -> {
            Project saved = call.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(PROJECT_ID);
            }
            return saved;
        });
        when(userContextService.getCurrentUser()).thenReturn(actor);
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

    /**
     * The trail opens on the status the project was created in. Without this entry a project's
     * first recorded status would be whatever it was patched to, and "created in this state" and
     * "moved into this state" would be indistinguishable, which is the exact confusion the trail
     * exists to end.
     */
    @Test
    void addProject_opensTheTrailOnTheStatusTheProjectWasCreatedIn() {
        ProjectCreationDto dto = validDto();
        dto.setStatus(ProjectCreationStatus.onHold);

        service.addProject(dto, null);

        verify(statusTransitionRecorder).recordCreation(
                eq(ProjectService.HISTORY_ENTITY_TYPE), eq(PROJECT_ID), eq(organization),
                eq(ProjectCreationStatus.onHold.name()), eq(actor));
    }

    /** The default status is recorded as the created status, not left blank. */
    @Test
    void addProject_recordsTheDefaultStatusWhenThePayloadNamesNone() {
        ProjectCreationDto dto = validDto();
        dto.setStatus(null);

        service.addProject(dto, null);

        verify(statusTransitionRecorder).recordCreation(
                anyString(), any(), any(), eq(ProjectCreationStatus.upcoming.name()), any());
    }

    /**
     * The transition the whole thing was built for. It has to name both ends, the organization the
     * project belongs to, and the user who made it: "approved" with no actor answers half of the
     * question a client asks.
     */
    @Test
    void approvingAProject_recordsTheTransitionWithBothStatusesAndTheActor() {
        Project project = draftProject(ProjectCreationStatus.upcoming);

        patch(project, Map.of("status", ProjectCreationStatus.approved.name()));

        verify(statusTransitionRecorder).recordChange(
                eq(ProjectService.HISTORY_ENTITY_TYPE), eq(PROJECT_ID), eq(organization),
                eq(ProjectCreationStatus.upcoming.name()),
                eq(ProjectCreationStatus.approved.name()),
                eq(actor), isNull());
    }

    /**
     * Recording is not limited to approval. Approval is the transition that carries behaviour, but
     * a project put on hold or cancelled raises the same question of who did it and when.
     */
    @Test
    void anyOtherStatusChange_isRecordedToo() {
        Project project = draftProject(ProjectCreationStatus.open);

        patch(project, Map.of("status", ProjectCreationStatus.cancelled.name()));

        verify(statusTransitionRecorder).recordChange(
                anyString(), any(), any(),
                eq(ProjectCreationStatus.open.name()),
                eq(ProjectCreationStatus.cancelled.name()),
                any(), any());
    }

    /**
     * A patch that touched no status still reaches the recorder, with the two ends equal. Which
     * of the two decides that no entry is written matters: the service does not try to work out
     * whether the status moved, so no caller can forget to, and
     * {@code StatusTransitionRecorderIT} pins that an entry whose ends are equal is dropped.
     *
     * <p>The write stamp is set either way, because a patch that renamed the project is still a
     * write somebody made.
     */
    @Test
    void aPatchThatChangesNoStatus_stampsTheWriteAndLeavesTheRecorderToDropIt() {
        Project project = draftProject(ProjectCreationStatus.open);

        patch(project, Map.of("projectName", "Renamed"));

        verify(statusTransitionRecorder).recordChange(
                anyString(), any(), any(),
                eq(ProjectCreationStatus.open.name()),
                eq(ProjectCreationStatus.open.name()),
                any(), any());
        assertThat(project.getUpdatedAt()).isNotNull();
        assertThat(project.getUpdatedBy()).isEqualTo(ACTOR_ID);
    }

    /**
     * An approval the project's state does not allow is refused, and nothing reaches the trail. A
     * recorded approval that did not happen is worse than no record at all.
     */
    @Test
    void aRefusedApproval_recordsNothing() {
        Project project = draftProject(ProjectCreationStatus.upcoming);
        project.setProjectAddress("Chennai");
        project.setProjectState(null);

        assertThatThrownBy(() -> patch(project, Map.of("status", ProjectCreationStatus.approved.name())))
                .hasMessageContaining("cannot be approved without a state");

        verify(statusTransitionRecorder, never()).recordChange(
                anyString(), any(), any(), any(), any(), any(), any());
    }

    /**
     * The create path stamps the creator as well as the writer. {@code created_at} has been on the
     * project all along and never said who made it.
     */
    @Test
    void addProject_stampsTheCreatorAndTheWriter() {
        service.addProject(validDto(), null);

        Project saved = savedProject();
        assertThat(saved.getCreatedBy()).isEqualTo(ACTOR_ID);
        assertThat(saved.getUpdatedBy()).isEqualTo(ACTOR_ID);
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    /**
     * Nothing here requires a user. A write from a background path leaves the actor empty rather
     * than failing, the way {@code AssetMovement.movedBy} does, because a status that moved with
     * no recorded actor is still worth recording.
     */
    @Test
    void aWriteWithNoUserContext_recordsTheTransitionWithNoActor() {
        when(userContextService.getCurrentUser()).thenReturn(null);
        Project project = draftProject(ProjectCreationStatus.upcoming);

        patch(project, Map.of("status", ProjectCreationStatus.closed.name()));

        verify(statusTransitionRecorder).recordChange(
                anyString(), any(), any(), anyString(), anyString(), isNull(), isNull());
        assertThat(project.getUpdatedBy()).isNull();
    }

    /**
     * A patch that sets the state and approves in one call still records one transition, judged on
     * the state the patch is setting. The recording runs after the whole patch, like the approval
     * hook it follows.
     */
    @Test
    void aPatchThatSetsTheStateAndApprovesTogether_recordsOneTransition() {
        Project project = draftProject(ProjectCreationStatus.upcoming);
        project.setProjectAddress("Chennai");
        project.setProjectState(null);

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("status", ProjectCreationStatus.approved.name());
        updates.put("projectState", "Tamil Nadu");

        patch(project, updates);

        verify(statusTransitionRecorder).recordChange(
                anyString(), any(), any(),
                eq(ProjectCreationStatus.upcoming.name()),
                eq(ProjectCreationStatus.approved.name()),
                any(), any());
    }

    /**
     * The team is part of the project, so changing it is a write like any other. A stamp that
     * covered only the field patch would report a project as untouched since last month while its
     * team was rebuilt yesterday, which is a wrong answer rather than a missing one.
     */
    @Test
    void addingAnEmployeeToTheTeam_stampsTheWrite() {
        Project project = draftProject(ProjectCreationStatus.open);
        Employee employee = teamMember();
        when(repository.findByIdAndOrganization_Id(PROJECT_ID, ORG_ID)).thenReturn(Optional.of(project));
        when(employeeRepository.findByIdAndOrganizationId(EMPLOYEE_ID, ORG_ID))
                .thenReturn(Optional.of(employee));

        service.addEmployeeToProject(PROJECT_ID, EMPLOYEE_ID);

        assertThat(project.getUpdatedAt()).isNotNull();
        assertThat(project.getUpdatedBy()).isEqualTo(ACTOR_ID);
    }

    /** The same on the way out. */
    @Test
    void removingAnEmployeeFromTheTeam_stampsTheWrite() {
        Project project = draftProject(ProjectCreationStatus.open);
        Employee employee = teamMember();
        project.getEmployees().add(employee);
        when(repository.findByIdAndOrganization_Id(PROJECT_ID, ORG_ID)).thenReturn(Optional.of(project));
        when(employeeRepository.findByIdAndOrganizationId(EMPLOYEE_ID, ORG_ID))
                .thenReturn(Optional.of(employee));

        service.removeEmployeeFromProject(PROJECT_ID, EMPLOYEE_ID);

        assertThat(project.getUpdatedAt()).isNotNull();
        assertThat(project.getUpdatedBy()).isEqualTo(ACTOR_ID);
    }

    /**
     * A team change is not a status change, so it must not appear in the status trail. The trail
     * is only readable if what is in it is what it says it holds.
     */
    @Test
    void changingTheTeam_recordsNoStatusTransition() {
        Project project = draftProject(ProjectCreationStatus.open);
        Employee employee = teamMember();
        when(repository.findByIdAndOrganization_Id(PROJECT_ID, ORG_ID)).thenReturn(Optional.of(project));
        when(employeeRepository.findByIdAndOrganizationId(EMPLOYEE_ID, ORG_ID))
                .thenReturn(Optional.of(employee));

        service.addEmployeeToProject(PROJECT_ID, EMPLOYEE_ID);

        verify(statusTransitionRecorder, never()).recordChange(
                anyString(), any(), any(), any(), any(), any(), any());
    }

    private Employee teamMember() {
        Employee employee = new Employee();
        employee.setId(EMPLOYEE_ID);
        employee.setEmployeeName("Site Engineer");
        employee.setOrganization(organization);
        return employee;
    }

    private void patch(Project project, Map<String, Object> updates) {
        when(repository.findByIdAndOrganization_Id(PROJECT_ID, ORG_ID)).thenReturn(Optional.of(project));
        service.partialUpdateAProject(updates, PROJECT_ID, null, "PROJECT");
    }

    private Project draftProject(ProjectCreationStatus status) {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setProjectName("Draft");
        project.setProjectAddress("12 Mount Road, Mylapore, Tamil Nadu");
        project.setProjectState("Tamil Nadu");
        project.setStatus(status);
        project.setOrganization(organization);
        return project;
    }

    private ProjectCreationDto validDto() {
        ProjectCreationDto dto = new ProjectCreationDto();
        dto.setProjectName("Riverside Tower");
        dto.setProjectAddress("12 Mount Road, Mylapore, Tamil Nadu");
        dto.setProjectState("Tamil Nadu");
        return dto;
    }

    private Project savedProject() {
        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
