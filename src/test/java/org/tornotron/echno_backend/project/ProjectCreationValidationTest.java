package org.tornotron.echno_backend.project;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.finance.ledger.repositories.CustomerRepository;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.dto.ProjectCreationDto;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;
import org.tornotron.echno_backend.project.mapper.ProjectMapper;
import org.tornotron.echno_backend.user.UserContextService;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.common.history.StatusTransitionRepository;
import org.tornotron.echno_backend.common.history.mapper.StatusTransitionMapper;
import org.tornotron.echno_backend.common.payload.PayloadValidator;

/**
 * Both project controllers take the create payload as the JSON string part of a multipart
 * request and deserialize it by hand, so Spring never binds a bean and never validates one. The
 * constraints on {@link ProjectCreationDto} were therefore decorative. These pin that the
 * service runs them itself, and that a rejected payload never reaches the repository.
 *
 * <p>A real Hibernate Validator with mocked collaborators: the point is whether the constraints
 * fire, which needs a genuine validator but no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class ProjectCreationValidationTest {

    private static ValidatorFactory factory;
    private Validator validator;

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
        validator = factory.getValidator();
        service = new ProjectService(repository, organizationRepository, employeeRepository,
                attachmentService, projectMapper, employeeMapper, eventPublisher,
                customerRepository, new PayloadValidator(validator), userContextService,
                statusTransitionRecorder, statusTransitionRepository, statusTransitionMapper);
    }

    private ProjectCreationDto validDto() {
        ProjectCreationDto dto = new ProjectCreationDto();
        dto.setProjectName("Riverside Tower");
        dto.setProjectAddress("12 Mount Road, Mylapore");
        dto.setStatus(ProjectCreationStatus.upcoming);
        return dto;
    }

    @Test
    void addProject_rejectsAMissingName_beforeTouchingTheRepository() {
        ProjectCreationDto dto = validDto();
        dto.setProjectName("  ");

        assertThatThrownBy(() -> service.addProject(dto, null))
                .isInstanceOf(ConstraintViolationException.class);

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void addProject_rejectsAnAddressOverTheLimit() {
        ProjectCreationDto dto = validDto();
        dto.setProjectAddress("x".repeat(256));

        assertThatThrownBy(() -> service.addProject(dto, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void addProject_rejectsAnImpossibleLatitude() {
        // The create path had no range check at all; only the patch path did.
        ProjectCreationDto dto = validDto();
        dto.setProjectLatitude(120f);

        assertThatThrownBy(() -> service.addProject(dto, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void addProject_rejectsAPostalCodeOverTheLimit() {
        ProjectCreationDto dto = validDto();
        dto.setProjectPostalCode("6000041234567890123");

        assertThatThrownBy(() -> service.addProject(dto, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void addProject_acceptsAnAddressOfTwoHundredAndFiftyFiveCharacters() {
        // The old cap was 50, which had no room for a street, a city and a state on one line.
        // Failing past validation means it got through to the tenant lookup, which is mocked
        // empty here; what matters is that it is not a constraint failure.
        ProjectCreationDto dto = validDto();
        dto.setProjectAddress("x".repeat(255));

        assertThatThrownBy(() -> service.addProject(dto, null))
                .isNotInstanceOf(ConstraintViolationException.class);
    }
}
