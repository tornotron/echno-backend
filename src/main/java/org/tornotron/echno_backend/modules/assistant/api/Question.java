package org.tornotron.echno_backend.modules.assistant.api;

/**
 * The question a provider is asked, as the user typed it. The scope it is asked under (project and
 * period) travels separately, because the pipeline resolves that in Java rather than leaving it to
 * the model.
 */
public record Question(String text) {

    public Question {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("A question needs text");
        }
    }
}
