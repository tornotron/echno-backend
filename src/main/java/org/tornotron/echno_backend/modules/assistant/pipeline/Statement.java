package org.tornotron.echno_backend.modules.assistant.pipeline;

import java.util.List;

/**
 * One sentence of an answer, with what backs it. A {@code FACT} is a claim about the data and
 * must cite the evidence it rests on; an {@code INFERENCE} is the model's reading of the facts
 * and a {@code RECOMMENDATION} is advice, and both are shown apart from the facts.
 */
public record Statement(String text, Kind kind, List<String> evidenceIds) {

    public enum Kind {
        FACT, INFERENCE, RECOMMENDATION
    }

    public Statement {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
