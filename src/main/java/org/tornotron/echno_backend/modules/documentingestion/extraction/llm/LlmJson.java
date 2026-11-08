package org.tornotron.echno_backend.modules.documentingestion.extraction.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads the JSON object out of a model's answer. The prompt asks for JSON only, and a model may still
 * wrap it in a code fence or a sentence, so the reader takes the outermost braces. It never relies on
 * {@code response_format}.
 */
public final class LlmJson {

    private LlmJson() {
    }

    /** The answer's JSON object, or {@link InvalidAnswerException}. */
    public static JsonNode object(String answer, ObjectMapper objectMapper) {
        if (answer == null) {
            throw new InvalidAnswerException("The answer is empty");
        }
        int open = answer.indexOf('{');
        int close = answer.lastIndexOf('}');
        if (open < 0 || close < open) {
            throw new InvalidAnswerException("The answer holds no JSON object");
        }
        try {
            JsonNode node = objectMapper.readTree(answer.substring(open, close + 1));
            if (node == null || !node.isObject()) {
                throw new InvalidAnswerException("The answer is not a JSON object");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new InvalidAnswerException("The answer is not valid JSON", e);
        }
    }
}
