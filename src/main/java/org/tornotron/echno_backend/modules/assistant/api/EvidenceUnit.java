package org.tornotron.echno_backend.modules.assistant.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One citable fact a provider found.
 *
 * @param id         unique and stable, for example {@code attendance:2026-08-14:project-42}; the
 *                   answer cites it and the validator checks every citation against the set
 * @param providerId the provider that produced it
 * @param kind       what sort of evidence it is, for example {@code attendance-day-summary}
 * @param values     field name to value, in the units the provider's {@link FieldSpec}s declare;
 *                   iteration order is insertion order, so the text built from it is reproducible
 * @param source     where it came from
 * @param relevance  how well it fits the question, from 0 to 1; structured data that matches
 *                   exactly is 1
 */
public record EvidenceUnit(
        String id,
        String providerId,
        String kind,
        Map<String, Object> values,
        SourceRef source,
        double relevance) {

    public EvidenceUnit {
        if (id == null || id.isBlank() || providerId == null || providerId.isBlank()
                || kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("Evidence needs an id, a provider and a kind");
        }
        if (source == null) {
            throw new IllegalArgumentException("Evidence " + id + " needs a source a user can open");
        }
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Evidence " + id + " carries no values");
        }
        if (!(relevance >= 0.0 && relevance <= 1.0)) {
            throw new IllegalArgumentException("Relevance is between 0 and 1: " + relevance);
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        values.forEach((name, value) -> {
            if (name == null || name.isBlank() || value == null) {
                throw new IllegalArgumentException("Evidence " + id + " has an unnamed or null value");
            }
            copy.put(name, value);
        });
        values = Collections.unmodifiableMap(copy);
    }
}
