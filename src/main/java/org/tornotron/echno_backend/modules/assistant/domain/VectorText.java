package org.tornotron.echno_backend.modules.assistant.domain;

/**
 * A vector in the text form CockroachDB reads, {@code [0.1,0.2,0.3]}, for the similarity query's
 * {@code CAST(:q AS VECTOR)}.
 *
 * <p>A non-finite component is refused rather than written: a vector holding NaN or infinity has
 * no meaningful distance to anything, and would only surface later as a wrong or missing result.
 */
public final class VectorText {

    private VectorText() {
    }

    public static String of(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("A vector needs at least one component");
        }
        StringBuilder text = new StringBuilder(vector.length * 8).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (!Float.isFinite(vector[i])) {
                throw new IllegalArgumentException("Component " + i + " of the vector is not a finite number");
            }
            if (i > 0) {
                text.append(',');
            }
            text.append(vector[i]);
        }
        return text.append(']').toString();
    }
}
