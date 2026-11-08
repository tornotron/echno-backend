package org.tornotron.echno_backend.common.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Set;

/**
 * The parts of a chat-completions response body that say what the model returned and why it
 * stopped, read without any opinion on what the message text means.
 *
 * <p>Parsing never fails on a missing field: an absent or non-text value comes back null, so a
 * caller can tell "the endpoint did not report usage" from "it reported zero". It fails only
 * when there is no JSON to read.
 *
 * @param content          {@code choices[0].message.content} when it is text, else null
 * @param finishReason     {@code choices[0].finish_reason}, falling back to {@code stop_reason}
 * @param promptTokens     {@code usage.prompt_tokens}
 * @param completionTokens {@code usage.completion_tokens}
 * @param totalTokens      {@code usage.total_tokens}, or the sum of the two above when only they
 *                         were reported
 * @param model            the top-level {@code model} field
 */
public record ChatCompletionEnvelope(String content,
                                     String finishReason,
                                     Integer promptTokens,
                                     Integer completionTokens,
                                     Integer totalTokens,
                                     String model) {

    /** Stop reasons that mean the model was cut off at the token cap, lower-cased. */
    private static final Set<String> TRUNCATED_STOP_REASONS = Set.of("length", "max_tokens");

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Whether the provider said it stopped at the token cap rather than at the end of its answer.
     * A truncated answer is never complete, whatever its text looks like.
     */
    public boolean truncated() {
        return finishReason != null && TRUNCATED_STOP_REASONS.contains(finishReason.toLowerCase(Locale.ROOT));
    }

    /**
     * Reads the envelope out of a response body.
     *
     * @throws MalformedEnvelopeException when the body is empty or is not JSON
     */
    public static ChatCompletionEnvelope parse(String body) throws MalformedEnvelopeException {
        if (body == null || body.isBlank()) {
            throw new MalformedEnvelopeException(true, "The response body was empty.", null);
        }

        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            throw new MalformedEnvelopeException(false, "The response body was not JSON.", e);
        }

        JsonNode choice = root.path("choices").path(0);
        JsonNode content = choice.path("message").path("content");

        // Providers are not consistent about the field name: OpenAI and the DigitalOcean
        // Gradient endpoint report finish_reason, some gateways relay stop_reason instead.
        JsonNode finishReason = choice.path("finish_reason");
        if (!finishReason.isTextual()) {
            finishReason = choice.path("stop_reason");
        }

        JsonNode usage = root.path("usage");
        Integer prompt = intOrNull(usage.path("prompt_tokens"));
        Integer completion = intOrNull(usage.path("completion_tokens"));
        Integer total = intOrNull(usage.path("total_tokens"));
        if (total == null && prompt != null && completion != null) {
            total = prompt + completion;
        }

        JsonNode model = root.path("model");

        return new ChatCompletionEnvelope(
                content.isTextual() ? content.asText() : null,
                finishReason.isTextual() ? finishReason.asText() : null,
                prompt,
                completion,
                total,
                model.isTextual() ? model.asText() : null);
    }

    private static Integer intOrNull(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToInt() ? node.asInt() : null;
    }

    /** The body held no JSON to read. */
    public static final class MalformedEnvelopeException extends Exception {

        private final boolean emptyBody;

        MalformedEnvelopeException(boolean emptyBody, String message, Throwable cause) {
            super(message, cause);
            this.emptyBody = emptyBody;
        }

        /** True when the body was empty, false when it was present but not JSON. */
        public boolean emptyBody() {
            return emptyBody;
        }
    }
}
