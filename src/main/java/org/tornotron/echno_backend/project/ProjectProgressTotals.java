package org.tornotron.echno_backend.project;

/**
 * One project's average task progress, as returned by the grouped read in
 * {@link org.tornotron.echno_backend.task.TaskRepository#averageTaskProgressByProjectIds}.
 *
 * <p>A row exists only for a project that has at least one task carrying a progress value, so a
 * project with no tasks, or with tasks that have never been given one, is absent from the result
 * rather than present with a zero. {@link ProjectProgressLookup} is what turns that absence back
 * into the {@code 0.0} the full DTO has always reported.
 *
 * @param projectId The project these totals belong to.
 * @param averageProgress The mean of its tasks' progress values, ignoring tasks with none.
 */
public record ProjectProgressTotals(Long projectId, Double averageProgress) {
}
