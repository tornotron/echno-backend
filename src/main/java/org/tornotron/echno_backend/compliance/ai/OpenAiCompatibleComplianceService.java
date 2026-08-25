package org.tornotron.echno_backend.compliance.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.tornotron.echno_backend.compliance.domain.ComplianceRule;
import org.tornotron.echno_backend.project.Project;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.List;

/**
 * Wraps the OpenAI-compatible chat-completions call that decides which candidate
 * compliance rules apply to a project. The endpoint is any service speaking the
 * OpenAI Chat Completions schema (DigitalOcean Gradient serverless inference by
 * default, or OpenAI, or a self-hosted OSS-model gateway), selected purely through
 * {@code compliance.ai.*} config.
 *
 * <p>The service is deliberately fail-soft: it never throws into the approval flow.
 * If the AI is disabled, has no API key, or the call/parse fails, it logs and
 * returns an empty list, and the caller generates nothing for that project (a
 * manual regenerate can be retried once the key is set).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAiCompatibleComplianceService {

    private final ComplianceAiProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Asks the model which of the candidate rules apply to the project. Returns one
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
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(project, state, candidateRules);

            String requestBody = buildRequestBody(systemPrompt, userPrompt);

            RestClient client = RestClient.builder()
                    .requestFactory(requestFactory())
                    .baseUrl(props.getBaseUrl())
                    .build();

            String responseJson = client.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            String responseText = extractText(responseJson);
            return parseSuggestions(responseText);
        } catch (Exception e) {
            log.error("Compliance AI call failed for project {}: {}", project.getId(), e.getMessage());
            return List.of();
        }
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        String proxyHost = props.getProxyHost();
        if (proxyHost != null && !proxyHost.isBlank()) {
            int proxyPort = props.getProxyPort();
            factory.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
            log.info("Compliance AI egress routed through HTTP proxy {}:{}", proxyHost, proxyPort);
        } else {
            log.debug("Compliance AI egress is direct (no proxy configured)");
        }
        return factory;
    }

    /**
     * Builds the OpenAI chat-completions request body with the system/user message
     * split. Jackson assembles the JSON so message content is escaped correctly.
     */
    private String buildRequestBody(String systemPrompt, String userPrompt) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", props.getModel());
        root.put("max_tokens", props.getMaxTokens());
        root.put("temperature", props.getTemperature());

        ArrayNode messages = root.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", systemPrompt);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt);

        return objectMapper.writeValueAsString(root);
    }

    private String buildSystemPrompt() {
        return "You are a construction-compliance assistant for India. Decide which statutory "
                + "compliances apply to a construction project, reasoning ONLY over the candidate "
                + "rules provided. Do not invent compliances outside this list.";
    }

    private String buildUserPrompt(Project project, String state, List<ComplianceRule> rules) {
        StringBuilder sb = new StringBuilder();
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

    /** Pulls the assistant text out of {@code choices[0].message.content}. */
    private String extractText(String responseJson) throws Exception {
        if (responseJson == null || responseJson.isBlank()) {
            return "";
        }
        JsonNode root = objectMapper.readTree(responseJson);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        return content.isTextual() ? content.asText() : "";
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
