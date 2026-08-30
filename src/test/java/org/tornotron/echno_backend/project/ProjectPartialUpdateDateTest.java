package org.tornotron.echno_backend.project;

import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.finance.ledger.repositories.CustomerRepository;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.mapper.ProjectMapper;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Date handling in the project partial-update switch.
 *
 * <p>The switch handled ten keys and no dates at all, so a user editing a project's start or end
 * date got a 200 and no change. The entity has had both columns throughout and the client has been
 * sending them, which is the part that makes this a bug rather than a missing feature.
 *
 * <p>Plain Mockito, no Spring context, so this adds nothing to the cached-context heap.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectPartialUpdateDateTest {

    @Mock private ProjectRepository repository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private ProjectMapper projectMapper;
    @Mock private EmployeeMapper employeeMapper;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private CustomerRepository customerRepository;
    @Mock private Validator validator;

    @Mock
    private UserContextService userContextService;

    @Mock
    private StatusTransitionRecorder statusTransitionRecorder;

    @InjectMocks private ProjectService service;

    private Project updateWith(Map<String, Object> updates) {
        Project project = new Project();
        TenantContext.setCurrentOrgId(1L);
        try {
            when(repository.findByIdAndOrganization_Id(any(), any())).thenReturn(Optional.of(project));
            when(repository.save(any(Project.class))).thenAnswer(i -> i.getArgument(0));
            service.partialUpdateAProject(updates, 12L, null, "PROJECT");
        } finally {
            TenantContext.clear();
        }
        return project;
    }

    @Test
    @DisplayName("applies startDate, which the switch used to ignore entirely")
    void appliesStartDate() {
        Project project = updateWith(Map.of("startDate", "2026-08-27T09:00:00"));

        assertThat(project.getStartDate()).isEqualTo(LocalDateTime.of(2026, 8, 27, 9, 0));
    }

    @Test
    @DisplayName("applies endDate, which the switch used to ignore entirely")
    void appliesEndDate() {
        Project project = updateWith(Map.of("endDate", "2027-03-31T18:00:00"));

        assertThat(project.getEndDate()).isEqualTo(LocalDateTime.of(2027, 3, 31, 18, 0));
    }

    @Test
    @DisplayName("leaves the other fields it already handled alone")
    void stillAppliesTheExistingFields() {
        Project project = updateWith(Map.of(
                "projectName", "Marina Towers",
                "startDate", "2026-08-27T09:00:00"));

        assertThat(project.getProjectName()).isEqualTo("Marina Towers");
        assertThat(project.getStartDate()).isEqualTo(LocalDateTime.of(2026, 8, 27, 9, 0));
    }

    @Test
    @DisplayName("rejects a UTC startDate rather than storing a shifted time")
    void rejectsUtcStartDate() {
        assertThatThrownBy(() -> updateWith(Map.of("startDate", "2026-08-27T03:30:00.000Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no timezone offset");
    }
}
