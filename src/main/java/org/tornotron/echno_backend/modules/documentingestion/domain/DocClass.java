package org.tornotron.echno_backend.modules.documentingestion.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * The 13 document classes this version reads (design section 4). Anything else is {@code OTHER}:
 * stored, listed and downloadable, and not read further.
 */
public enum DocClass {
    NIT,
    ITB,
    ELIGIBILITY,
    GCC,
    SCC,
    CONTRACT_DATA,
    SCOPE,
    BOQ,
    SPECIFICATION,
    DRAWING,
    MILESTONE_SCHEDULE,
    CORRIGENDUM,
    OTHER;

    /** The class with this name, ignoring case and spaces or hyphens for underscores. */
    public static Optional<DocClass> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String name = raw.strip().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (DocClass c : values()) {
            if (c.name().equals(name)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}
