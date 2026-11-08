package org.tornotron.echno_backend.common.llm;

import java.time.Duration;

/**
 * What one chat completion returned. Fields the endpoint did not report are null.
 *
 * @param outcome          how the call ended
 * @param httpStatus       the HTTP status, or null when no response arrived
 * @param content          {@code choices[0].message.content}, or null
 * @param finishReason     {@code choices[0].finish_reason} (or {@code stop_reason}), or null
 * @param promptTokens     {@code usage.prompt_tokens}, or null
 * @param completionTokens {@code usage.completion_tokens}, or null
 * @param totalTokens      {@code usage.total_tokens}, or the sum of the other two when only they
 *                         were reported, or null
 * @param model            the model as the response named it, which can differ from the slug sent
 * @param latency          wall clock from sending the request to reading the whole body;
 *                         zero when nothing was sent
 * @param detail           one plain sentence on what went wrong, or null for {@code OK}. Never
 *                         holds the API key
 * @param responseBody     the raw body when the endpoint answered with a success status, else null
 */
public record ChatCompletionResult(ChatCompletionOutcome outcome,
                                   Integer httpStatus,
                                   String content,
                                   String finishReason,
                                   Integer promptTokens,
                                   Integer completionTokens,
                                   Integer totalTokens,
                                   String model,
                                   Duration latency,
                                   String detail,
                                   String responseBody) {

    public boolean isOk() {
        return outcome == ChatCompletionOutcome.OK;
    }

    static ChatCompletionResult notConfigured() {
        return new ChatCompletionResult(ChatCompletionOutcome.NOT_CONFIGURED, null, null, null, null, null,
                null, null, Duration.ZERO,
                "The chat-completions endpoint is switched off or has no API key, so nothing was sent.",
                null);
    }

    static ChatCompletionResult failed(ChatCompletionOutcome outcome, Integer httpStatus,
                                       Duration latency, String detail) {
        return new ChatCompletionResult(outcome, httpStatus, null, null, null, null, null, null,
                latency, detail, null);
    }

    static ChatCompletionResult answered(ChatCompletionOutcome outcome, int httpStatus,
                                         ChatCompletionEnvelope envelope, Duration latency,
                                         String detail, String responseBody) {
        return new ChatCompletionResult(outcome, httpStatus, envelope.content(), envelope.finishReason(),
                envelope.promptTokens(), envelope.completionTokens(), envelope.totalTokens(),
                envelope.model(), latency, detail, responseBody);
    }
}
