package org.tornotron.echno_backend.modules.documentingestion.extraction.llm;

/** A complete answer that is not the JSON the prompt asked for. The message names what was wrong, never the answer. */
public class InvalidAnswerException extends RuntimeException {

    public InvalidAnswerException(String message) {
        super(message);
    }

    public InvalidAnswerException(String message, Throwable cause) {
        super(message, cause);
    }
}
