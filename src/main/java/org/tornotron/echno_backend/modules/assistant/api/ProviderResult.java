package org.tornotron.echno_backend.modules.assistant.api;

import java.util.List;

/**
 * What a provider found. Three outcomes, and the difference between the last two is the point:
 * "no attendance was recorded" and "attendance could not be read" lead to different answers, and
 * the decline text is built from which one it was.
 */
public sealed interface ProviderResult {

    /** One or more citable facts. Never empty: finding nothing is {@link Empty}. */
    record Evidence(List<EvidenceUnit> units) implements ProviderResult {
        public Evidence {
            if (units == null || units.isEmpty()) {
                throw new IllegalArgumentException("Evidence with no units is Empty");
            }
            units = List.copyOf(units);
        }
    }

    /** The source was read and holds nothing for this question. Not an error. */
    record Empty(String lookedFor) implements ProviderResult {
        public Empty {
            if (lookedFor == null || lookedFor.isBlank()) {
                throw new IllegalArgumentException("An empty result says what was looked for");
            }
        }
    }

    /** The source could not be read, or the question could not be put to it. */
    record Unavailable(String reason) implements ProviderResult {
        public Unavailable {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("An unavailable result says why");
            }
        }
    }
}
