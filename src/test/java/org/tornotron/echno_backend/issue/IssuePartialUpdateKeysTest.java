package org.tornotron.echno_backend.issue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.issue.enums.IssueStatus;
import org.tornotron.echno_backend.issue.enums.IssueType;
import org.tornotron.echno_backend.issue.mapper.IssueMapper;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.task.TaskRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * The issue update endpoint reads its payload into a map and applies it with a {@code switch} over
 * the keys, so an unrecognised key is not a 400: it is dropped, and the caller is told the update
 * succeeded.
 *
 * <p>That is how changing an issue's type through the product came to do nothing. echno-core sends
 * the field as {@code issueType}; the switch only named {@code type}. Create escaped it because
 * create binds a real DTO and {@code @JsonAlias("issueType")} catches the name there, and a map
 * has no property binding for an alias to attach to. So the two paths disagreed and only one of
 * them was wrong.
 *
 * <p>These pin both halves: the update takes the name the client actually sends, and the keys the
 * endpoint has no field for stay dropped rather than becoming a rejection that would fail every
 * update the deployed web app makes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IssuePartialUpdateKeysTest {

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

    private IssueService service;
    private Issue existing;

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        service = new IssueService(issueRepository, taskRepository, attachmentService,
                issueMapper, employeeRepository, new PayloadValidator(validator));

        TenantContext.setCurrentOrgId(1L);

        Organization organization = new Organization();
        organization.setId(1L);
        existing = new Issue();
        existing.setId(7L);
        existing.setTitle("Honeycombing on the block A raft");
        existing.setType(IssueType.quality);
        existing.setStatus(IssueStatus.open);
        existing.setOrganization(organization);

        when(issueRepository.findByIdAndOrganization_Id(anyLong(), anyLong()))
                .thenReturn(Optional.of(existing));
        when(issueRepository.save(any(Issue.class))).thenAnswer(call -> call.getArgument(0));
        when(employeeRepository.findByIdAndOrganizationId(anyLong(), anyLong()))
                .thenReturn(Optional.of(new Employee()));
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

    @Test
    void update_changesTheTypeWhenTheClientNamesItIssueType() {
        // The name every published echno-core sends. Before this was handled the call returned 200
        // and the type stayed exactly as it was.
        service.partialUpdateAnIssue(Map.of("issueType", "safety"), 7L, null, "ISSUE_ATTACHMENTS");

        assertThat(existing.getType()).isEqualTo(IssueType.safety);
    }

    @Test
    void update_stillChangesTheTypeUnderItsCanonicalName() {
        service.partialUpdateAnIssue(Map.of("type", "safety"), 7L, null, "ISSUE_ATTACHMENTS");

        assertThat(existing.getType()).isEqualTo(IssueType.safety);
    }

    @Test
    void update_appliesTheOtherFieldsAlongsideTheAliasedOne() {
        // Ordering matters here: the aliased key must not shadow or short-circuit the rest.
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("title", "Honeycombing along the north edge");
        updates.put("issueType", "safety");
        updates.put("status", "resolved");

        service.partialUpdateAnIssue(updates, 7L, null, "ISSUE_ATTACHMENTS");

        assertThat(existing.getTitle()).isEqualTo("Honeycombing along the north edge");
        assertThat(existing.getType()).isEqualTo(IssueType.safety);
        assertThat(existing.getStatus()).isEqualTo(IssueStatus.resolved);
    }

    @Test
    void update_acceptsTheKeysItHasNoFieldForRatherThanRefusingTheRequest() {
        // echno-core puts attachments: [] in the JSON part of every issue update, and the form
        // offers a priority the entity has no column for. Refusing an unrecognised key would turn
        // every update the deployed web app makes into a 400, so they are dropped and logged.
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("attachments", java.util.List.of());
        updates.put("priority", "high");
        updates.put("somethingNobodyDeclared", "x");
        updates.put("issueType", "safety");

        service.partialUpdateAnIssue(updates, 7L, null, "ISSUE_ATTACHMENTS");

        assertThat(existing.getType()).isEqualTo(IssueType.safety);
    }
}
