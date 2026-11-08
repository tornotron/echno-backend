package org.tornotron.echno_backend.modules.inspections.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * What an {@link ObservationIntakeCheck} is told about one finding before it is stored.
 *
 * <p>Plain values, so a module outside {@code inspections} can read it without reaching the
 * module's internals. {@code source} and each region's {@code shape} are the upper-case enum names
 * ({@code AI}, {@code BOX}).
 *
 * @param source       who produced the finding
 * @param modelName    the model name the producer gave, or null
 * @param modelVersion the model version the producer gave, or null
 * @param category     the category the producer gave, or null
 * @param regions      the validated regions, in the order posted; never null
 */
public record IntakeCandidate(String source, String modelName, String modelVersion, String category,
                              List<Region> regions) {

    public static final String SOURCE_AI = "AI";

    public IntakeCandidate {
        regions = regions == null ? List.of() : List.copyOf(regions);
    }

    /**
     * One region of the finding.
     *
     * @param classKey the class the region names
     * @param shape    {@code BOX} or {@code POLYGON}
     * @param score    the producer's score, or null
     */
    public record Region(String classKey, String shape, BigDecimal score) {}

    /** A model's output: posted as AI, or carrying a score on any region. */
    public boolean isModelOutput() {
        return SOURCE_AI.equals(source) || regions.stream().anyMatch(r -> r.score() != null);
    }
}
