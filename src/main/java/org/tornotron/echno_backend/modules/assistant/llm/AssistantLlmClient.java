package org.tornotron.echno_backend.modules.assistant.llm;

import java.util.List;

/**
 * The one door to a language model. The pipeline asks it for text and reads the answer as JSON
 * itself, so a different endpoint, or the fake the tests use, changes nothing around it.
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

    /**
     * Embeds a list of texts into vector representations.
     *
     * @param texts the texts to embed
     * @return a list of float arrays corresponding to the input texts
     * @throws LlmUnavailableException when the embeddings endpoint could not be reached or failed
     */
    default List<float[]> embed(List<String> texts) {
        return List.of();
    }

    /**
     * Embeds a single text string into a vector representation.
     */
    default float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[0];
        }
        List<float[]> res = embed(List.of(text));
        return res.isEmpty() ? new float[0] : res.get(0);
    }
}
