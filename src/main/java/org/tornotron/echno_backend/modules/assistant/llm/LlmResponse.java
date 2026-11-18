package org.tornotron.echno_backend.modules.assistant.llm;

/** What one chat call returned: the text, and what it cost in tokens, for the audit row. */
public record LlmResponse(String content, int promptTokens, int completionTokens, String model, String modelVersion) {
}
