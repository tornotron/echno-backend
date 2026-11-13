package org.tornotron.echno_backend.modules.assistant.api;

/**
 * One field a provider's evidence carries: its name as the source system names it, the unit its
 * value is in, and what it means. A value is never relabelled or converted on the way to the
 * answer, so a reader of the answer can trace every number back to the field it came from.
 *
 * @param name    the field's name in the evidence values, for example {@code totalWorkMinutes}
 * @param unit    the unit of the value, for example {@code minutes} or {@code records}; never blank,
 *                because a bare number is the thing this record exists to prevent
 * @param meaning one sentence on what the value is
 */
public record FieldSpec(String name, String unit, String meaning) {

    public FieldSpec {
        requireText(name, "name");
        requireText(unit, "unit");
        requireText(meaning, "meaning");
    }

    private static void requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A field's " + what + " is required");
        }
    }
}
