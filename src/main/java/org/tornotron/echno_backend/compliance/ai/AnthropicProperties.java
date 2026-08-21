package org.tornotron.echno_backend.compliance.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the Anthropic Claude call that drives compliance generation.
 * Bound from the {@code anthropic.*} block in application.yml. The API key is a
 * secret supplied at deploy time via {@code ANTHROPIC_API_KEY}; when it is blank
 * (or {@code enabled} is false) the AI service no-ops, so the application runs
 * normally without a key configured.
 */
@Data
@Component
@ConfigurationProperties(prefix = "anthropic")
public class AnthropicProperties {

    /** Secret API key. Empty by default; set via the ANTHROPIC_API_KEY env var. */
    private String apiKey = "";

    /** Model id to call. */
    private String model = "claude-opus-5";

    /** Upper bound on tokens generated in the response. */
    private long maxTokens = 4096;

    /** Master switch; when false the AI call is skipped and generation no-ops. */
    private boolean enabled = true;
}
