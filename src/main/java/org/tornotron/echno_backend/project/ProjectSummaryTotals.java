package org.tornotron.echno_backend.project;

/**
 * The figures a project list derives from a project's collections, as returned by the read in
 * {@link ProjectRepository#summaryTotalsByProjectIds}: the average task progress, how many tasks
 * there are and how many employees are on the team.
 *
 * <p>One row comes back for every project asked for. {@code averageProgress} is null for a
 * project with no task carrying a progress value; {@link ProjectSummaryLookup} turns that back
 * into the {@code 0.0} the full DTO has always reported. The counts are never null: a project
 * with nothing to count reads as zero.
 *
 * @param projectId The project these totals belong to.
 * @param averageProgress The mean of its tasks' progress values, ignoring tasks with none.
 * @param taskCount How many tasks it has.
 * @param memberCount How many employees are on its team.
 */
public record ProjectSummaryTotals(Long projectId, Double averageProgress, long taskCount,
                                   long memberCount) {
}
