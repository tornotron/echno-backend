package org.tornotron.echno_backend.modules.assistant.pipeline;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.tornotron.echno_backend.modules.assistant.api.Subject;

/**
 * What the model understood the question to be: the intent in a phrase, which kinds of data it
 * is about, the project as the person typed it, the period if one was named, a person if one was
 * named, whether it needs several steps, and what was too unclear to act on.
 */
public record Interpretation(
        String intent,
        Set<Subject> subjects,
        String projectRef,
        LocalDate from,
        LocalDate to,
        String personRef,
        boolean multiStep,
        List<String> ambiguous) {

    public Interpretation {
        subjects = subjects == null ? Set.of() : Set.copyOf(subjects);
        ambiguous = ambiguous == null ? List.of() : List.copyOf(ambiguous);
    }

    public boolean hasPeriod() {
        return from != null && to != null;
    }
}
