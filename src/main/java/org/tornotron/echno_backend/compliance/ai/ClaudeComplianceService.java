package org.tornotron.echno_backend.compliance.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.compliance.domain.ComplianceRule;
import org.tornotron.echno_backend.project.Project;

import java.util.List;

/**
 * Wraps the Anthropic Claude call that decides which candidate compliance rules
 * apply to a project. The service is deliberately fail-soft: it never throws into
 * the approval flow. If the AI is disabled, has no API key, or the call/parse
 * fails, it logs and returns an empty list, and the caller generates nothing for
 * that project (a manual regenerate can be retried once the key is set).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeComplianceService {

    private final AnthropicProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Asks Claude which of the candidate rules apply to the project. Returns one
     * suggestion per rule the model reasoned about; an empty list means "generate
     * nothing" (either the AI is off/unconfigured or the call failed).
     */
    public List<ComplianceSuggestion> suggestCompliances(Project project,
                                                         String state,
                                                         List<ComplianceRule> candidateRules) {
        if (!props.isEnabled() || props.getApiKey() == null || props.getApiKey().isBlank()) {
            log.info("Compliance AI disabled or API key not configured; skipping AI suggestion for project {}",
                    project.getId());
            return List.of();
        }
        if (candidateRules == null || candidateRules.isEmpty()) {
            return List.of();
        }

        try {
            AnthropicClient client = AnthropicOkHttpClient.builder()
                    .apiKey(props.getApiKey())
                    .build();

            String prompt = buildPrompt(project, state, candidateRules);

            MessageCreateParams params = MessageCreateParams.builder()
                    .model(props.getModel())
                    .maxTokens(props.getMaxTokens())
                    .addUserMessage(prompt)
                    .build();

            Message message = client.messages().create(params);
            String responseText = extractText(message);
            return parseSuggestions(responseText);
        } catch (Exception e) {
            log.error("Compliance AI call failed for project {}: {}", project.getId(), e.getMessage());
            return List.of();
        }
    }

    private String buildPrompt(Project project, String state, List<ComplianceRule> rules) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a construction-compliance assistant for India. Decide which statutory ")
                .append("compliances apply to a construction project, reasoning ONLY over the candidate ")
                .append("rules provided. Do not invent compliances outside this list.\n\n");
        sb.append("Project:\n");
        sb.append("- name: ").append(project.getProjectName()).append('\n');
        sb.append("- state: ").append(state).append('\n');
        sb.append("- projectType: ").append(project.getProjectType()).append('\n');
        if (project.getProjectAddress() != null) {
            sb.append("- address: ").append(project.getProjectAddress()).append('\n');
        }
        sb.append("\nEach compliance belongs to one of three lifecycle phases: pre-construction ")
                .append("(obtained before work starts), ongoing (held or renewed during construction), ")
                .append("and post-construction (obtained on completion).\n\n");
        sb.append("Candidate rules (JSON):\n");
        sb.append("[\n");
        for (int i = 0; i < rules.size(); i++) {
            ComplianceRule r = rules.get(i);
            sb.append("  {")
                    .append("\"ruleCode\": \"").append(r.getCode()).append("\", ")
                    .append("\"name\": \"").append(escape(r.getName())).append("\", ")
                    .append("\"phase\": \"").append(r.getPhase().getValue()).append("\", ")
                    .append("\"defaultRiskLevel\": \"").append(r.getDefaultRiskLevel().getValue()).append("\", ")
                    .append("\"description\": \"").append(escape(r.getDescription())).append("\", ")
                    .append("\"authority\": \"").append(escape(r.getAuthority())).append("\"}")
                    .append(i < rules.size() - 1 ? ",\n" : "\n");
        }
        sb.append("]\n\n");
        sb.append("Respond with STRICT JSON only, no prose and no markdown fences: a JSON array where ")
                .append("each element is {\"ruleCode\": string, \"applies\": boolean, ")
                .append("\"riskLevel\": one of [low, medium, high, critical], ")
                .append("\"resolutionOptions\": array of short strings, ")
                .append("\"rationale\": short string, ")
                .append("\"phase\": one of [pre-construction, ongoing, post-construction]}. ")
                .append("Include one element for every candidate rule.");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    /** Concatenates the text of every text content block in the message. */
    private String extractText(Message message) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : message.content()) {
            block.text().ifPresent(t -> sb.append(t.text()));
        }
        return sb.toString();
    }

    /**
     * Parses the model's response into suggestions. Tolerates the model wrapping the
     * array in prose or code fences by slicing from the first '[' to the last ']'.
     */
    private List<ComplianceSuggestion> parseSuggestions(String responseText) throws Exception {
        if (responseText == null || responseText.isBlank()) {
            return List.of();
        }
        int start = responseText.indexOf('[');
        int end = responseText.lastIndexOf(']');
        if (start < 0 || end <= start) {
            log.warn("Compliance AI response contained no JSON array; got: {}", responseText);
            return List.of();
        }
        String json = responseText.substring(start, end + 1);
        ComplianceSuggestion[] parsed = objectMapper.readValue(json, ComplianceSuggestion[].class);
        return List.of(parsed);
    }
}
