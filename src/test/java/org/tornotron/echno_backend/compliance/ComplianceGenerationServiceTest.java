package org.tornotron.echno_backend.compliance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.compliance.ai.OpenAiCompatibleComplianceService;
import org.tornotron.echno_backend.compliance.repository.ComplianceRuleRepository;
import org.tornotron.echno_backend.inspection.mapper.InspectionMapper;
import org.tornotron.echno_backend.inspection.repositories.InspectionRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.project.enums.ProjectType;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the precondition handling in {@link ComplianceGenerationService}. Each
 * unmet precondition must surface a clear 4xx exception (rather than silently returning
 * an empty list), so the frontend's error toast can tell the user exactly what to fix.
 * Pure Mockito, no database.
 */
@ExtendWith(MockitoExtension.class)
class ComplianceGenerationServiceTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long ORG_ID = 7L;

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ComplianceRuleRepository ruleRepository;
    @Mock
    private OpenAiCompatibleComplianceService complianceAiService;
    @Mock
    private InspectionRepository inspectionRepository;
    @Mock
    private EntryNumberGenerator numberGen;
    @Mock
    private TenantEntityHelper tenantEntityHelper;
    @Mock
    private InspectionMapper inspectionMapper;

    @InjectMocks
    private ComplianceGenerationService service;

    private Project project(ProjectType type, String address) {
        Project project = new Project();
        project.setProjectName("Test Project");
        project.setProjectType(type);
        project.setProjectAddress(address);
        return project;
    }

    @Test
    void projectNotFound_throwsResourceNotFound() {
        when(projectRepository.findByIdAndOrganization_Id(PROJECT_ID, ORG_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generateForProject(PROJECT_ID, ORG_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("No project with id " + PROJECT_ID);
    }

    @Test
    void noProjectType_throwsInvalidRequest() {
        when(projectRepository.findByIdAndOrganization_Id(PROJECT_ID, ORG_ID))
                .thenReturn(Optional.of(project(null, "12 Mount Road, Chennai, Tamil Nadu")));

        assertThatThrownBy(() -> service.generateForProject(PROJECT_ID, ORG_ID))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("no type set");
    }

    @Test
    void unresolvableAddress_throwsInvalidRequest() {
        when(projectRepository.findByIdAndOrganization_Id(PROJECT_ID, ORG_ID))
                .thenReturn(Optional.of(project(ProjectType.RESIDENTIAL, "No state named here")));

        assertThatThrownBy(() -> service.generateForProject(PROJECT_ID, ORG_ID))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("recognised state");
    }

    @Test
    void noRulesForJurisdiction_throwsInvalidRequest() {
        when(projectRepository.findByIdAndOrganization_Id(PROJECT_ID, ORG_ID))
                .thenReturn(Optional.of(project(ProjectType.RESIDENTIAL, "12 Mount Road, Chennai, Tamil Nadu")));
        when(ruleRepository.findByStateIgnoreCaseAndProjectTypeAndActiveTrue("Tamil Nadu", ProjectType.RESIDENTIAL))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.generateForProject(PROJECT_ID, ORG_ID))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("No compliance rules are registered")
                .hasMessageContaining("Tamil Nadu");
    }

    @Test
    void aiNotConfigured_throwsInvalidRequest() {
        when(projectRepository.findByIdAndOrganization_Id(PROJECT_ID, ORG_ID))
                .thenReturn(Optional.of(project(ProjectType.RESIDENTIAL, "12 Mount Road, Chennai, Tamil Nadu")));
        when(ruleRepository.findByStateIgnoreCaseAndProjectTypeAndActiveTrue(anyString(), any()))
                .thenReturn(List.of(new org.tornotron.echno_backend.compliance.domain.ComplianceRule()));
        when(complianceAiService.suggestCompliances(any(Project.class), anyString(), any()))
                .thenReturn(List.of());
        when(complianceAiService.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.generateForProject(PROJECT_ID, ORG_ID))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("AI service is not configured");
    }

    @Test
    void aiConfiguredButNoSuggestions_returnsEmpty() {
        when(projectRepository.findByIdAndOrganization_Id(PROJECT_ID, ORG_ID))
                .thenReturn(Optional.of(project(ProjectType.RESIDENTIAL, "12 Mount Road, Chennai, Tamil Nadu")));
        when(ruleRepository.findByStateIgnoreCaseAndProjectTypeAndActiveTrue(anyString(), any()))
                .thenReturn(List.of(new org.tornotron.echno_backend.compliance.domain.ComplianceRule()));
        when(complianceAiService.suggestCompliances(any(Project.class), anyString(), any()))
                .thenReturn(List.of());
        when(complianceAiService.isConfigured()).thenReturn(true);

        assertThat(service.generateForProject(PROJECT_ID, ORG_ID)).isEmpty();
    }
}
