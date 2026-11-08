package org.tornotron.echno_backend.modules.inspections.compliance.ai;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.llm.LlmEndpointProperties;

/**
 * Configuration for the compliance generator's chat-completions calls, bound from the
 * {@code compliance.ai.*} block in application.yml.
 *
 * <p>The endpoint settings (base URL, API key, model, proxy, timeouts and the {@code enabled}
 * switch) are inherited from {@link LlmEndpointProperties}, which every caller of the shared
 * {@link org.tornotron.echno_backend.common.llm.ChatCompletionClient} reads from the same prefix.
 * What is declared here is compliance's own: its token cap, its temperature and how it batches
 * the rule catalogue.
 *
 * <p>The API key is a secret supplied at deploy time via {@code COMPLIANCE_AI_API_KEY}; when it
 * is blank (or {@code enabled} is false) the AI service no-ops, so the application runs normally
 * without a key configured.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Component
@ConfigurationProperties(prefix = "compliance.ai")
public class ComplianceAiProperties extends LlmEndpointProperties {

    /** Upper bound on tokens generated in the response. */
    private int maxTokens = 4096;

    /**
     * How many candidate rules to ask about in one call. Zero or less asks about the whole
     * catalogue in a single call, which is what the code did before batching and is kept
     * only as an escape hatch.
     *
     * <p>Ten comes from measuring the configured endpoint rather than from taste. The answer
     * costs about 57 completion tokens per rule, so ten rules spends roughly 600 of the 4096
     * available and a run does not approach the cap until about 72 rules in one call, which is
     * where a measured run was in fact cut off. Ten rules also takes about 20 seconds against
     * a 60-second read timeout. Both margins are deliberately large, because the number that
     * has to hold is not the average rule but the worst rule in someone's catalogue.
     */
    private int batchSize = 10;

    /**
     * How many batches of one run may be in flight at once. One runs them strictly in
     * sequence.
     *
     * <p>Four, because the endpoint was measured serving four simultaneous calls in about the
     * time it served one, so the run costs roughly one batch instead of the sum of them. It is
     * configuration rather than a constant because that is a property of the endpoint and its
     * key, not of this application: an endpoint that rate-limits per key, or a self-hosted
     * gateway with one GPU, wants this set to one and should not need a build to get it.
     */
    private int batchConcurrency = 4;

    /** Sampling temperature; kept low for deterministic structured output. */
    private double temperature = 0.2;
}
