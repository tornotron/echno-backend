package org.tornotron.echno_backend.compliance.ai;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.compliance.CompliancePhase;
import org.tornotron.echno_backend.compliance.domain.ComplianceRule;
import org.tornotron.echno_backend.inspection.ComplianceRiskLevel;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.enums.ProjectType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The compliance AI must be safe to run without a configured endpoint: when disabled
 * or missing an API key it returns no suggestions rather than attempting a call. These
 * are pure unit tests; they never touch the network.
 */
class OpenAiCompatibleComplianceServiceTest {

    private Project sampleProject() {
        Project project = new Project();
        project.setProjectName("Sample");
        project.setProjectAddress("Chennai, Tamil Nadu");
        project.setProjectType(ProjectType.RESIDENTIAL);
        return project;
    }

    private List<ComplianceRule> sampleRules() {
        ComplianceRule rule = new ComplianceRule();
        rule.setState("Tamil Nadu");
        rule.setProjectType(ProjectType.RESIDENTIAL);
        rule.setPhase(CompliancePhase.PRE_CONSTRUCTION);
        rule.setCode("TN-BPA");
        rule.setName("Building Plan Approval");
        rule.setDefaultRiskLevel(ComplianceRiskLevel.CRITICAL);
        return List.of(rule);
    }

    @Test
    void returnsEmptyWhenDisabled() {
        ComplianceAiProperties props = new ComplianceAiProperties();
        props.setEnabled(false);
        props.setApiKey("sk-should-not-be-used");
        OpenAiCompatibleComplianceService service = new OpenAiCompatibleComplianceService(props);

        List<ComplianceSuggestion> result =
                service.suggestCompliances(sampleProject(), "Tamil Nadu", sampleRules());

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyWhenApiKeyBlank() {
        ComplianceAiProperties props = new ComplianceAiProperties();
        props.setEnabled(true);
        props.setApiKey("   ");
        OpenAiCompatibleComplianceService service = new OpenAiCompatibleComplianceService(props);

        List<ComplianceSuggestion> result =
                service.suggestCompliances(sampleProject(), "Tamil Nadu", sampleRules());

        assertThat(result).isEmpty();
    }
}
