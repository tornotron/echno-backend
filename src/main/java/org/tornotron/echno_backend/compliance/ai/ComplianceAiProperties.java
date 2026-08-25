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
