package org.tornotron.echno_backend.modules.assistant.llm;

/**
 * The model could not be reached, was not configured, or answered with an error. The message is
 * written for the person asking: it names no endpoint and carries nothing from the upstream
 * response, which belongs in the log.
 */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message) {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
