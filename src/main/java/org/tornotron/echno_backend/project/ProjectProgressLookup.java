package org.tornotron.echno_backend.project;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The average task progress for a whole page of projects, read once and handed to the mapper.
 *
 * <p>{@link ProjectDto} reports a project's progress as the mean of its tasks' progress values,
 * which {@link ProjectProgressCalculator} computes from {@code project.getTasks()}. That is one
 * {@code Double}, and reaching it costs the project's entire task collection: on a list page,
 * every task of every project on the page, so that a number can be averaged and the tasks
 * discarded. The summary projection asks the database for the average instead, once for the whole
 * page, and passes it down as a MapStruct {@code @Context} in the shape
 * {@link org.tornotron.echno_backend.inventoryTransaction.MaterialStockLookup} established.
 *
 * <p>The two agree by construction. {@code AVG} ignores nulls exactly as the calculator filters
 * them out, and a project with nothing to average is absent from the grouped result, which
 * {@link #progressOf} reads back as the {@code 0.0} the calculator returns for an empty list.
 */
public final class ProjectProgressLookup {

    private static final ProjectProgressLookup EMPTY = new ProjectProgressLookup(Map.of());

    private final Map<Long, Double> byProjectId;

    private ProjectProgressLookup(Map<Long, Double> byProjectId) {
        this.byProjectId = byProjectId;
    }

    /**
     * A lookup holding nothing, so every project reads as no progress.
     *
     * <p>For a project that cannot have tasks yet, and for tests. It is not a fallback for a
     * caller that forgot to read: that would silently report a running project at zero.
     *
     * @return The empty lookup.
     */
    public static ProjectProgressLookup none() {
        return EMPTY;
    }

    /**
     * Builds a lookup from the rows of a grouped read.
     *
     * @param totals The per-project averages, at most one row per project.
     * @return A lookup over those averages.
     */
    public static ProjectProgressLookup of(Collection<ProjectProgressTotals> totals) {
        if (totals == null || totals.isEmpty()) {
            return EMPTY;
        }
        return new ProjectProgressLookup(totals.stream()
                .filter(row -> row.projectId() != null && row.averageProgress() != null)
                .collect(Collectors.toMap(ProjectProgressTotals::projectId,
                        ProjectProgressTotals::averageProgress, (first, second) -> first)));
    }

    /**
     * The average task progress for a project.
     *
     * @param projectId The project to read, which may be null for an entity not yet persisted.
     * @return The average, or {@code 0.0} where the project has nothing to average.
     */
    public Double progressOf(Long projectId) {
        return byProjectId.getOrDefault(projectId, 0.0);
    }

    /**
     * Whether the lookup holds no rows at all.
     *
     * @return {@code true} when every project reads as zero.
     */
    public boolean isEmpty() {
        return byProjectId.isEmpty();
    }
}
