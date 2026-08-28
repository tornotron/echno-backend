package org.tornotron.echno_backend.compliance.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.tornotron.echno_backend.common.exception.ComplianceAiException;
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
 * <h2>What an empty result means, and what it no longer means</h2>
 *
 * <p>The service stays fail-soft about being switched off: with the AI disabled, with no
 * API key, or with no candidate rules it returns an empty list and the caller generates
 * nothing. Those are configuration states rather than failures, and they must not break
 * project approval.
 *
 * <p>Every other outcome now raises {@link ComplianceAiException}. An empty list used to
 * stand for the call failing, the response being cut short by the token cap and the JSON
 * failing to parse as well, so the worst outcome available (the rule catalogue outgrows
 * the token budget, and from then on every run produces nothing) reached the user as a
 * successful run in which no compliances happened to apply. A model that assessed every
 * rule and found none applicable answers with one element per rule carrying
 * {@code applies: false}, never with an empty array, so nothing legitimate is lost by
 * treating an empty or short answer as a failure.
 *
 * <p>{@link ComplianceResponseReader} holds the checks that decide this, and its javadoc
 * explains how a truncated answer is recognised from the response alone.
 */
@Slf4j
@Service
public class OpenAiCompatibleComplianceService {

    private final ComplianceAiProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ComplianceResponseReader responseReader;

    public OpenAiCompatibleComplianceService(ComplianceAiProperties props) {
        this.props = props;
        this.responseReader = new ComplianceResponseReader(objectMapper, props.getMaxTokens());
    }

    /**
     * Whether the AI service is switched on and has an API key, decided purely from
     * config with no network call. When this is false the service always no-ops, so
     * callers can tell "AI not configured" apart from "AI ran and found nothing".
     */
    public boolean isConfigured() {
        return props.isEnabled() && props.getApiKey() != null && !props.getApiKey().isBlank();
    }

    /**
     * Asks the model which of the candidate rules apply to the project, returning one
     * suggestion per candidate rule.
     *
     * <p>Returns an empty list only when there was nothing to ask: the AI is disabled or
     * unconfigured, or there are no candidate rules.
     *
     * @throws ComplianceAiException when the call failed, or the answer was cut short,
     *                               unparseable, or did not cover every candidate rule
     */
    public List<ComplianceSuggestion> suggestCompliances(Project project,
                                                         String state,
                                                         List<ComplianceRule> candidateRules) {
        if (!isConfigured()) {
            log.info("Compliance AI disabled or API key not configured; skipping AI suggestion for project {}",
                    project.getId());
            return List.of();
        }
        if (candidateRules == null || candidateRules.isEmpty()) {
            return List.of();
        }

        String responseJson = callModel(project, state, candidateRules);
        return responseReader.read(responseJson, candidateRules);
    }

    /**
     * Issues the chat completion and returns the raw response body. Every way the call
     * itself can fail (a connect or read timeout, a proxy refusing, a 4xx or 5xx from the
     * endpoint) arrives here and leaves as a {@link ComplianceAiException}, so the caller
     * is told the run failed instead of being handed an empty result to interpret.
     */
    private String callModel(Project project, String state, List<ComplianceRule> candidateRules) {
        try {
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(project, state, candidateRules);

            String requestBody = buildRequestBody(systemPrompt, userPrompt);

            RestClient client = RestClient.builder()
                    .requestFactory(requestFactory())
                    .baseUrl(props.getBaseUrl())
                    .build();

            return client.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            // The message stays out of the response body on purpose: for an HTTP error the
            // RestClient exception carries the provider's own response text, which names the
            // endpoint and is more of the upstream than an API client has any use for. The log
            // is where it belongs, and the cause chain keeps it for whoever is debugging.
            log.error("Compliance AI call failed for project {}: {}", project.getId(), e.getMessage(), e);
            throw new ComplianceAiException(
                    "The compliance AI service could not be reached or returned an error, so no "
                            + "compliances were generated. Try again in a moment.", e);
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
                + "rules provided. Do not invent compliances outside this list. Assess every rule "
                + "you are given, including the ones that do not apply, and keep each rationale to "
                + "one short sentence so the whole answer fits within the response limit.";
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
                .append("Include one element for every candidate rule: the array must hold exactly ")
                .append(rules.size())
                .append(" elements, one per ruleCode listed above, and none may be omitted.");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
