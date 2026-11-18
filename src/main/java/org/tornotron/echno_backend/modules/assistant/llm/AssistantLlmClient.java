package org.tornotron.echno_backend.modules.assistant.llm;

/**
 * The one door to a language model. The pipeline asks it for text and reads the answer as JSON
 * itself, so a different endpoint, or the fake the tests use, changes nothing around it.
 *
 * <p>Embeddings are not here yet: nothing in the ask path embeds a question until documents are
 * ingested, and the method arrives with that work.
 */
public interface AssistantLlmClient {

    /**
     * Whether a call can be made at all, decided from configuration with no network call. False is
     * a state to report ("the assistant is not configured"), not a failure to retry.
     */
    boolean isConfigured();

    /**
     * One chat completion.
     *
     * @throws LlmUnavailableException when the model could not be reached or answered with an
     *                                 error, with a message safe to show a caller
     */
    LlmResponse chat(String system, String user);
}
