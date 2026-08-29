package org.tornotron.echno_backend.compliance.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the OpenAI-compatible chat-completions call that drives
 * compliance generation. Bound from the {@code compliance.ai.*} block in
 * application.yml. The endpoint is any service that speaks the OpenAI Chat
 * Completions schema (DigitalOcean Gradient serverless inference by default, but
 * equally OpenAI, a self-hosted vLLM/Ollama gateway, or similar).
 *
 * <p>The API key is a secret supplied at deploy time via
 * {@code COMPLIANCE_AI_API_KEY}; when it is blank (or {@code enabled} is false)
 * the AI service no-ops, so the application runs normally without a key
 * configured. The model slug is a fully overridable default, never hardcoded in
 * the service.
 */
@Data
@Component
@ConfigurationProperties(prefix = "compliance.ai")
public class ComplianceAiProperties {

    /** Base URL of the OpenAI-compatible API; {@code /chat/completions} is appended to it. */
    private String baseUrl = "https://inference.do-ai.run/v1";

    /** Secret API key. Empty by default; set via the COMPLIANCE_AI_API_KEY env var. */
    private String apiKey = "";

    /** Model id (slug) to call. */
    private String model = "llama3.3-70b-instruct";

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

    /** Master switch; when false the AI call is skipped and generation no-ops. */
    private boolean enabled = true;

    /**
     * Optional HTTP forward-proxy host for reaching the inference endpoint. Empty by
     * default (direct egress, the production behaviour on DigitalOcean). Set it in
     * environments where the backend has no direct internet and must egress through a
     * forward proxy (for example the IITM lab, where a haproxy on the host relays out).
     */
    private String proxyHost = "";

    /** Proxy port used only when {@code proxyHost} is set. */
    private int proxyPort = 3128;
}
