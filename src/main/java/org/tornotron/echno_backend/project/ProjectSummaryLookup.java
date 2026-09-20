package org.tornotron.echno_backend.project;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The derived figures for a whole page of projects, read once and handed to the mapper.
 *
 * <p>{@link ProjectDto} reports a project's progress as the mean of its tasks' progress values,
 * which {@link ProjectProgressCalculator} computes from {@code project.getTasks()}, and the list
 * screens render how many members and tasks a project has, which the full view answers by
 * carrying both collections. Each is one number, and reaching it costs the project's entire
 * collection: on a list page, every task and every team member of every project on the page, so
 * that a number can be derived and the rows discarded. The summary projection asks the database
 * for the figures instead, once for the whole page, and passes them down as a MapStruct
 * {@code @Context} in the shape
 * {@link org.tornotron.echno_backend.inventoryTransaction.MaterialStockLookup} established.
 *
 * <p>Progress agrees with the calculator by construction. {@code AVG} ignores nulls exactly as
 * the calculator filters them out, and a project with nothing to average comes back with a null
 * average, which {@link #progressOf} reads back as the {@code 0.0} the calculator returns for an
 * empty list. The counts agree with the collections the full view carries: the tasks mapped to
 * the project and the employees on its team.
 */
public final class ProjectSummaryLookup {

    private static final ProjectSummaryLookup EMPTY = new ProjectSummaryLookup(Map.of());

    private final Map<Long, ProjectSummaryTotals> byProjectId;

    private ProjectSummaryLookup(Map<Long, ProjectSummaryTotals> byProjectId) {
        this.byProjectId = byProjectId;
    }

    /**
     * A lookup holding nothing, so every project reads as no progress, no tasks and no members.
     *
     * <p>For a project that cannot have tasks yet, and for tests. It is not a fallback for a
     * caller that forgot to read: that would silently report a running project at zero.
     *
     * @return The empty lookup.
     */
    public static ProjectSummaryLookup none() {
        return EMPTY;
    }

    /**
     * Builds a lookup from the rows of the read.
     *
     * @param totals The per-project figures, at most one row per project.
     * @return A lookup over those figures.
     */
    public static ProjectSummaryLookup of(Collection<ProjectSummaryTotals> totals) {
        if (totals == null || totals.isEmpty()) {
            return EMPTY;
        }
        return new ProjectSummaryLookup(totals.stream()
                .filter(row -> row.projectId() != null)
                .collect(Collectors.toMap(ProjectSummaryTotals::projectId, Function.identity(),
                        (first, second) -> first)));
    }

    /**
     * The average task progress for a project.
     *
     * @param projectId The project to read, which may be null for an entity not yet persisted.
     * @return The average, or {@code 0.0} where the project has nothing to average.
     */
    public Double progressOf(Long projectId) {
        ProjectSummaryTotals row = byProjectId.get(projectId);
        return row == null || row.averageProgress() == null ? 0.0 : row.averageProgress();
    }

    /**
     * How many tasks a project has.
     *
     * @param projectId The project to read.
     * @return The count, zero where the project is absent from the lookup.
     */
    public long taskCountOf(Long projectId) {
        ProjectSummaryTotals row = byProjectId.get(projectId);
        return row == null ? 0L : row.taskCount();
    }

    /**
     * How many employees are on a project's team.
     *
     * @param projectId The project to read.
     * @return The count, zero where the project is absent from the lookup.
     */
    public long memberCountOf(Long projectId) {
        ProjectSummaryTotals row = byProjectId.get(projectId);
        return row == null ? 0L : row.memberCount();
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
