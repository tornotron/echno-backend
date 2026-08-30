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
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.common.history.StatusTransitionRepository;
import org.tornotron.echno_backend.common.history.mapper.StatusTransitionMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.finance.ledger.repositories.CustomerRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.dto.ProjectCreationDto;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;
import org.tornotron.echno_backend.project.mapper.ProjectMapper;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Approval is the one project transition with anything behind it: a check that the project's
 * state is known, and the event that draws up its compliance inspections. Both live on the patch
 * path, so a create that wrote {@code approved} onto the row went round them, and no later patch
 * could make up for it, because the approval hook fires only on the transition INTO approved and
 * a project born approved never makes it. These pin that create refuses that value and lets the
 * rest through.
 *
 * <p>Plain Mockito with a real validator and no Spring context.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectCreateStatusTest {

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

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        service = new ProjectService(repository, organizationRepository, employeeRepository,
                attachmentService, projectMapper, employeeMapper, eventPublisher,
                customerRepository, new PayloadValidator(validator), userContextService,
                statusTransitionRecorder, statusTransitionRepository, statusTransitionMapper);

        TenantContext.setCurrentOrgId(1L);
        Organization organization = new Organization();
        organization.setId(1L);
        when(organizationRepository.findById(1L)).thenReturn(Optional.of(organization));
        when(repository.existsProjectByProjectName(anyString())).thenReturn(false);
        when(repository.save(any(Project.class))).thenAnswer(call -> call.getArgument(0));
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

    private ProjectCreationDto validDto() {
        ProjectCreationDto dto = new ProjectCreationDto();
        dto.setProjectName("Riverside Tower");
        dto.setProjectAddress("12 Mount Road, Mylapore, Tamil Nadu");
        dto.setProjectState("Tamil Nadu");
        return dto;
    }

    @Test
    void addProject_refusesAProjectAskedToBeCreatedAlreadyApproved() {
        // Nothing is written and nothing is published: an approved row with no event behind it is
        // the state that can never be put right, since the approval hook fires only on the
        // transition into approved and a project born approved never makes it.
        ProjectCreationDto dto = validDto();
        dto.setStatus(ProjectCreationStatus.approved);

        assertThatThrownBy(() -> service.addProject(dto, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("cannot be created already approved");

        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void addProject_startsAProjectUpcomingWhenThePayloadNamesNoStatus() {
        ProjectCreationDto dto = validDto();
        dto.setStatus(null);

        service.addProject(dto, null);

        assertThat(savedProject().getStatus()).isEqualTo(ProjectCreationStatus.upcoming);
    }

    @Test
    void addProject_keepsEveryStatusThatCarriesNoTransitionBehindIt() {
        // Only approved has a check and an event behind it, so narrowing further would refuse
        // what the create form offers today for no gain.
        for (ProjectCreationStatus status : ProjectCreationStatus.values()) {
            if (status == ProjectCreationStatus.approved) {
                continue;
            }
            ProjectCreationDto dto = validDto();
            dto.setStatus(status);

            assertThatCode(() -> service.addProject(dto, null)).doesNotThrowAnyException();
        }
    }

    private Project savedProject() {
        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
