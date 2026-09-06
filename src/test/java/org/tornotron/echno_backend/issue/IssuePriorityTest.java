package org.tornotron.echno_backend.issue;

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
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.issue.dto.IssueCreationDto;
import org.tornotron.echno_backend.issue.enums.IssuePriority;
import org.tornotron.echno_backend.issue.enums.IssueStatus;
import org.tornotron.echno_backend.issue.enums.IssueType;
import org.tornotron.echno_backend.issue.mapper.IssueMapper;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.task.TaskRepository;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An issue's priority, on the way in.
 *
 * <p>The issue form has rendered a Priority select (low, medium, high, critical) and submitted the
 * choice on create and on edit, while the backend had the field at no layer: not on the entity,
 * not on either payload, not as a case in the update switch and not as a column. The key was on
 * the update's deliberately-dropped list, so the write was accepted with a 200 and the choice went
 * nowhere. These pin both write paths, and the two ways the update can be asked for no priority at
 * all.
 *
 * <p>Plain Mockito with a real validator and no Spring context.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IssuePriorityTest {

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
    private Issue existing;

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

        existing = new Issue();
        existing.setId(7L);
        existing.setTitle("Honeycombing on the block A raft");
        existing.setType(IssueType.quality);
        existing.setStatus(IssueStatus.open);
        existing.setOrganization(organization);

        when(issueRepository.findByIdAndOrganization_Id(anyLong(), anyLong()))
                .thenReturn(Optional.of(existing));
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
    @DisplayName("create stores the priority the form submitted")
    void addIssue_storesThePriority() {
        IssueCreationDto dto = validDto();
        dto.setPriority(IssuePriority.critical);

        service.addIssue(dto, null);

        assertThat(savedIssue().getPriority()).isEqualTo(IssuePriority.critical);
    }

    @Test
    @DisplayName("create leaves the priority unset when the payload names none")
    void addIssue_leavesThePriorityUnsetWhenAbsent() {
        // No default is invented here. An issue raised by a client with no such control has no
        // priority, and saying it is medium would be a claim nobody made.
        service.addIssue(validDto(), null);

        assertThat(savedIssue().getPriority()).isNull();
    }

    @Test
    @DisplayName("partial update applies the priority, which the switch used to drop")
    void partialUpdate_appliesThePriority() {
        service.partialUpdateAnIssue(Map.of("priority", "high"), 7L, null, "ISSUE_ATTACHMENTS");

        assertThat(existing.getPriority()).isEqualTo(IssuePriority.high);
    }

    @Test
    @DisplayName("partial update accepts every member the form's select offers")
    void partialUpdate_acceptsEveryPriorityTheFormOffers() {
        for (IssuePriority priority : IssuePriority.values()) {
            service.partialUpdateAnIssue(Map.of("priority", priority.name()), 7L, null,
                    "ISSUE_ATTACHMENTS");

            assertThat(existing.getPriority()).isEqualTo(priority);
        }
    }

    @Test
    @DisplayName("partial update clears the priority when it is sent as an explicit null")
    void partialUpdate_clearsThePriorityOnAnExplicitNull() {
        existing.setPriority(IssuePriority.high);

        service.partialUpdateAnIssue(singletonUpdate("priority", null), 7L, null,
                "ISSUE_ATTACHMENTS");

        assertThat(existing.getPriority()).isNull();
    }

    @Test
    @DisplayName("partial update refuses a priority nobody defined with a 400, not a 500")
    void partialUpdate_refusesAnUnknownPriority() {
        assertThatThrownBy(() -> service.partialUpdateAnIssue(Map.of("priority", "urgent"), 7L,
                null, "ISSUE_ATTACHMENTS"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("not a valid issue priority");
    }

    @Test
    @DisplayName("partial update refuses a blank priority rather than treating it as a clear")
    void partialUpdate_refusesABlankPriority() {
        // An explicit null is the documented way to clear the field, and it is the only one. A
        // blank string names no member, so letting it through would clear the priority on a
        // payload the schema never described that way.
        existing.setPriority(IssuePriority.high);

        assertThatThrownBy(() -> service.partialUpdateAnIssue(Map.of("priority", "  "), 7L, null,
                "ISSUE_ATTACHMENTS"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("not a valid issue priority");

        assertThat(existing.getPriority()).isEqualTo(IssuePriority.high);
    }

    @Test
    @DisplayName("partial update refuses a priority sent as something other than a name")
    void partialUpdate_refusesAPriorityThatIsNotAString() {
        // The value comes out of a map, so a number would be a class cast and a 500 if it were
        // read straight as a String the way type and status are.
        assertThatThrownBy(() -> service.partialUpdateAnIssue(Map.of("priority", 2), 7L, null,
                "ISSUE_ATTACHMENTS"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("not a valid issue priority");
    }

    @Test
    @DisplayName("partial update leaves the priority alone when the payload omits it")
    void partialUpdate_leavesThePriorityAloneWhenAbsent() {
        existing.setPriority(IssuePriority.low);

        service.partialUpdateAnIssue(Map.of("title", "Honeycombing along the north edge"), 7L,
                null, "ISSUE_ATTACHMENTS");

        assertThat(existing.getPriority()).isEqualTo(IssuePriority.low);
    }

    /** {@link Map#of} refuses a null value, and an explicit null is what clears the priority. */
    private Map<String, Object> singletonUpdate(String key, Object value) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(key, value);
        return Collections.unmodifiableMap(updates);
    }

    private Issue savedIssue() {
        ArgumentCaptor<Issue> captor = ArgumentCaptor.forClass(Issue.class);
        verify(issueRepository).save(captor.capture());
        return captor.getValue();
    }
}
