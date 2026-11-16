package org.tornotron.echno_backend.modules.assistant.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

/**
 * Reads the JSON object out of a model's reply. Models wrap JSON in prose or a code fence often
 * enough that taking the text from the first brace to the last is worth doing; anything that
 * still is not an object is treated as no answer rather than guessed at.
 */
final class ModelJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelJson() {
    }

    static Optional<JsonNode> object(String reply) {
        if (reply == null) {
            return Optional.empty();
        }
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(reply.substring(start, end + 1));
            return node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** The text of a field, or null when it is missing, null or blank. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }
}
