package org.tornotron.echno_backend.modules.assistant.llm;

/**
 * The fixed words the pipeline's prompts start their sections with. They are in one place because
 * the prompts are written by the pipeline and read by {@link FakeLlmClient}, and the two must
 * agree to the character or the fake answers a different question than the one asked.
 */
public final class PromptMarkers {

    /** First token of the system prompt of the call that understands a question. */
    public static final String INTERPRET = "[INTERPRET]";

    /** First token of the system prompt of the call that writes an answer from evidence. */
    public static final String ANSWER = "[ANSWER]";

    /** Prefix of the line carrying the question, in both user prompts. */
    public static final String QUESTION = "QUESTION: ";

    /** Prefix of an evidence line: {@code [n] id=<id> kind=<kind> values=<json>}. */
    public static final String EVIDENCE_ID = " id=";

    public static final String EVIDENCE_VALUES = " values=";

    private PromptMarkers() {
    }
}
