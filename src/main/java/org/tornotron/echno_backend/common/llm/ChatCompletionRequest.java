package org.tornotron.echno_backend.common.llm;

import java.util.Objects;

/**
 * One chat completion to ask for: a system message, a user message and the limits for this call.
 *
 * <p>{@code maxTokens} is per call because callers size it differently against the same read
 * timeout. {@code temperature} is left out of the request when null, so the endpoint's default
 * applies. {@code jsonResponseFormat} adds {@code response_format: {"type": "json_object"}}; it is
 * off unless a caller asks, and no caller should rely on the endpoint honouring it.
 *
 * @param systemPrompt       the system message, never null
 * @param userPrompt         the user message, never null
 * @param maxTokens          upper bound on completion tokens for this call, at least 1
 * @param temperature        sampling temperature, or null for the endpoint's default
 * @param jsonResponseFormat whether to ask for a JSON object response
 */
public record ChatCompletionRequest(String systemPrompt,
                                    String userPrompt,
                                    int maxTokens,
                                    Double temperature,
                                    boolean jsonResponseFormat) {

    public ChatCompletionRequest {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(userPrompt, "userPrompt");
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be at least 1, was " + maxTokens);
        }
    }

    /** A request with no temperature and no response format. */
    public static ChatCompletionRequest of(String systemPrompt, String userPrompt, int maxTokens) {
        return new ChatCompletionRequest(systemPrompt, userPrompt, maxTokens, null, false);
    }

    public ChatCompletionRequest withTemperature(double value) {
        return new ChatCompletionRequest(systemPrompt, userPrompt, maxTokens, value, jsonResponseFormat);
    }

    public ChatCompletionRequest withJsonResponseFormat() {
        return new ChatCompletionRequest(systemPrompt, userPrompt, maxTokens, temperature, true);
    }
}
