package org.tornotron.echno_backend.modules.assistant.api;

import java.time.LocalDate;

/**
 * Where a question is looking: one project the caller may read, and a period. Already resolved and
 * entitlement-checked by the pipeline, so a provider treats it as given and still runs under the
 * caller's own tenant and authorities.
 *
 * @param projectId   the resolved project, or null for a provider that is not about a project
 * @param from        first day of the period, inclusive, or null with {@code to}
 * @param to          last day of the period, inclusive, or null with {@code from}
 * @param subjectHint what within the subject was asked about, for example a material's name, or null
 */
public record Scope(Long projectId, LocalDate from, LocalDate to, String subjectHint) {

    public Scope {
        if ((from == null) != (to == null)) {
            throw new IllegalArgumentException("A period needs both its first and its last day");
        }
        if (from != null && from.isAfter(to)) {
            throw new IllegalArgumentException("A period cannot end before it starts: " + from + " to " + to);
        }
    }
}
