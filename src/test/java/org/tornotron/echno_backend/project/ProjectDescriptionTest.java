package org.tornotron.echno_backend.project;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.finance.ledger.repositories.CustomerRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.dto.ProjectCreationDto;
import org.tornotron.echno_backend.project.mapper.ProjectMapper;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A project's description, on the way in.
 *
 * <p>The create and edit forms have both rendered a labelled description textarea and submitted
 * what was typed in it, while the backend had the field at no layer: not on the entity, not on
 * any payload, not as a case in the update switch and not as a column. The write was accepted
 * with a 200 and the text went nowhere, so reopening the project showed an empty box. These pin
 * both write paths.
 *
 * <p>Plain Mockito with a real validator and no Spring context.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectDescriptionTest {

    private static ValidatorFactory factory;

    @Mock private ProjectRepository repository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private ProjectMapper projectMapper;
    @Mock private EmployeeMapper employeeMapper;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private CustomerRepository customerRepository;
    @Mock private UserContextService userContextService;
    @Mock private StatusTransitionRecorder statusTransitionRecorder;
    @Mock private StatusTransitionRepository statusTransitionRepository;
    @Mock private StatusTransitionMapper statusTransitionMapper;

    private Validator validator;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
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
        return dto;
    }

    @Test
    @DisplayName("create stores the description the form submitted")
    void addProject_storesTheDescription() {
        ProjectCreationDto dto = validDto();
        dto.setDescription("Twelve-storey residential tower, two basement levels.");

        service.addProject(dto, null);

        assertThat(savedProject().getDescription())
                .isEqualTo("Twelve-storey residential tower, two basement levels.");
    }

    @Test
    @DisplayName("create treats a blank textarea as no description rather than an empty string")
    void addProject_treatsABlankDescriptionAsAbsent() {
        ProjectCreationDto dto = validDto();
        dto.setDescription("   ");

        service.addProject(dto, null);

        assertThat(savedProject().getDescription()).isNull();
    }

    @Test
    @DisplayName("create refuses a description past the payload's bound")
    void creationPayload_boundsTheDescriptionLength() {
        ProjectCreationDto dto = validDto();
        dto.setDescription("x".repeat(2001));

        Set<ConstraintViolation<ProjectCreationDto>> violations = validator.validate(dto);

        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .contains("description must be at most 2000 characters");
    }

    @Test
    @DisplayName("partial update applies the description, which the switch used to drop")
    void partialUpdate_appliesTheDescription() {
        Project project = updateWith(Map.of("description", "Revised scope: two towers, one podium."));

        assertThat(project.getDescription()).isEqualTo("Revised scope: two towers, one podium.");
    }

    @Test
    @DisplayName("partial update clears the description when the textarea is emptied")
    void partialUpdate_clearsTheDescriptionWhenBlank() {
        Project existing = new Project();
        existing.setDescription("Something written earlier.");

        Project project = updateWith(existing, singletonUpdate("description", ""));

        assertThat(project.getDescription()).isNull();
    }

    @Test
    @DisplayName("partial update refuses a description past the same bound the create payload uses")
    void partialUpdate_boundsTheDescriptionLength() {
        // No bean validation runs on the map the patch endpoint keeps, so without this the bound
        // would apply to creates alone and a project could be patched to any length at all.
        assertThatThrownBy(() -> updateWith(Map.of("description", "x".repeat(2001))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description must be at most 2000 characters");
    }

    @Test
    @DisplayName("partial update accepts a description exactly at the bound")
    void partialUpdate_acceptsADescriptionAtTheBound() {
        Project project = updateWith(Map.of("description", "x".repeat(2000)));

        assertThat(project.getDescription()).hasSize(2000);
    }

    @Test
    @DisplayName("partial update leaves the description alone when the payload omits it")
    void partialUpdate_leavesTheDescriptionAloneWhenAbsent() {
        Project existing = new Project();
        existing.setDescription("Something written earlier.");

        Project project = updateWith(existing, Map.of("projectName", "Marina Towers"));

        assertThat(project.getDescription()).isEqualTo("Something written earlier.");
        assertThat(project.getProjectName()).isEqualTo("Marina Towers");
    }

    /** {@link Map#of} refuses a null value, and a blank one is what an emptied textarea sends. */
    private Map<String, Object> singletonUpdate(String key, Object value) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(key, value);
        return Collections.unmodifiableMap(updates);
    }

    private Project updateWith(Map<String, Object> updates) {
        return updateWith(new Project(), updates);
    }

    private Project updateWith(Project project, Map<String, Object> updates) {
        when(repository.findByIdAndOrganization_Id(any(), any())).thenReturn(Optional.of(project));
        service.partialUpdateAProject(updates, 12L, null, "PROJECT");
        return project;
    }

    private Project savedProject() {
        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
