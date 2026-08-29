package org.tornotron.echno_backend.compliance.ai;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.exception.ComplianceAiException;
import org.tornotron.echno_backend.compliance.CompliancePhase;
import org.tornotron.echno_backend.compliance.domain.ComplianceRule;
import org.tornotron.echno_backend.inspection.ComplianceRiskLevel;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.enums.ProjectType;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The compliance AI must be safe to run without a configured endpoint: when disabled
 * or missing an API key it returns no suggestions rather than attempting a call.
 *
 * <p>No test here reaches an external endpoint. The timeout test is the only one that opens
 * a socket at all, and it is a loopback listener the test owns and closes.
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

    @Test
    void proxyDefaultsToDirectEgress() {
        ComplianceAiProperties props = new ComplianceAiProperties();

        assertThat(props.getProxyHost()).isEmpty();
        assertThat(props.getProxyPort()).isEqualTo(3128);
    }

    @Test
    void timeoutsDefaultToThePreviouslyHardcodedValues() {
        ComplianceAiProperties props = new ComplianceAiProperties();

        assertThat(props.getConnectTimeoutSeconds()).isEqualTo(10);
        assertThat(props.getReadTimeoutSeconds()).isEqualTo(60);
    }

    /**
     * The read timeout has to come from config, not from a constant. This is asserted
     * behaviourally rather than by reading the field back, because what matters is that the
     * value reaches the request factory: a property nothing consumes would satisfy a getter
     * test and change nothing about the call.
     *
     * <p>The server here accepts the connection and then never writes a byte, which is the
     * shape of a hung endpoint. With the timeout configured to one second the call fails in
     * about one second; against a hardcoded sixty it sits there for a minute, so the elapsed
     * bound is what separates the two.
     */
    @Test
    void readTimeoutComesFromConfiguration() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread accepter = new Thread(() -> {
                try {
                    // Hold the accepted socket open and silent until the test closes the server.
                    Socket accepted = server.accept();
                    Thread.sleep(120_000);
                    accepted.close();
                } catch (Exception ignored) {
                    // Closing the server socket at the end of the test lands here.
                }
            });
            accepter.setDaemon(true);
            accepter.start();

            ComplianceAiProperties props = new ComplianceAiProperties();
            props.setEnabled(true);
            props.setApiKey("sk-test");
            props.setBaseUrl("http://" + server.getInetAddress().getHostAddress() + ":" + server.getLocalPort() + "/v1");
            props.setReadTimeoutSeconds(1);
            OpenAiCompatibleComplianceService service = new OpenAiCompatibleComplianceService(props);

            long startedAt = System.nanoTime();
            assertThatThrownBy(() -> service.callModel(sampleProject(), "Tamil Nadu", sampleRules()))
                    .isInstanceOf(ComplianceAiException.class);
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(elapsedMillis)
                    .as("read timeout of 1s must be honoured, not the 60s default")
                    .isLessThan(15_000);
        }
    }

    @Test
    void proxyConfigIsOptionalAndDoesNotBreakTheNoOpPath() {
        ComplianceAiProperties props = new ComplianceAiProperties();
        props.setEnabled(false);
        props.setProxyHost("10.24.6.116");
        props.setProxyPort(3128);
        OpenAiCompatibleComplianceService service = new OpenAiCompatibleComplianceService(props);

        List<ComplianceSuggestion> result =
                service.suggestCompliances(sampleProject(), "Tamil Nadu", sampleRules());

        assertThat(result).isEmpty();
    }
}
