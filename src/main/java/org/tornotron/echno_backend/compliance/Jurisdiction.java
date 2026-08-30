package org.tornotron.echno_backend.compliance;

import org.tornotron.echno_backend.project.enums.ProjectType;

import java.util.Locale;

/**
 * The pair that decides which rules apply to a project: its state and its type.
 *
 * <p>There is one string form of that pair and it is built here, because two separate places
 * need it and they must agree. The sweep groups the rule catalogue by it, and every generation
 * job records the one it was accepted for, so that the sweep can tell a project reassessed
 * under a different jurisdiction from one that has simply not changed. Two hand-rolled key
 * builders that drifted apart would make every project look reassessed under a new
 * jurisdiction, which is a silent bill rather than a visible bug.
 *
 * <p>The state is lower-cased because the rule query matches it case-insensitively, so
 * {@code Tamil Nadu} and {@code TAMIL NADU} are the same jurisdiction and must produce the
 * same key. The type is not, because it is an enum constant and its case is fixed.
 */
public final class Jurisdiction {

    /** Column width for the stored form; well clear of the longest state name plus a type. */
    public static final int MAX_LENGTH = 120;

    private Jurisdiction() {
    }

    /**
     * The key for one jurisdiction, or null if either half is missing.
     *
     * <p>Null rather than a partial key on purpose: a project whose state cannot be resolved
     * has no jurisdiction at all, and giving it a key would let it compare equal to another
     * project in the same predicament.
     */
    public static String key(String state, ProjectType projectType) {
        if (state == null || state.isBlank() || projectType == null) {
            return null;
        }
        return state.trim().toLowerCase(Locale.ROOT) + "|" + projectType.name();
    }
}
