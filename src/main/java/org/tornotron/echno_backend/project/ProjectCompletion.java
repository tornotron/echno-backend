package org.tornotron.echno_backend.project;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.task.enums.TaskStatus;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Keeps a project's completed status in step with its tasks (ClickUp 14zdkkvzzwg).
 *
 * <p>The rule the product owner settled on 8 Nov 2026: a project is completed as a result of its
 * tasks being completed, and nobody but a system admin can mark one completed by hand. So:
 *
 * <ul>
 *   <li>When every task of a project is complete, a project that is still being worked on
 *       ({@link #ACTIVE}) becomes {@code completed}.</li>
 *   <li>When a project completed that way has a task that is no longer complete (one reopened, or
 *       a new one added), it goes back to {@code open}.</li>
 *   <li>A project a system admin completed by hand ({@link Project#getCompletedManually()}) is
 *       left as it is: closing a project before its work is finished is exactly what that
 *       override is for.</li>
 * </ul>
 *
 * <p>A task counts as complete when its status is {@code completed} or its progress has reached
 * 100. A project with no tasks never completes on its own, since there is no work to finish.
 * Every move is written to the project's status trail as a system change naming the rule.
 */
@Component
public class ProjectCompletion {

    /** Statuses a project is worked in, and so the ones that complete when the work does. */
    static final Set<ProjectCreationStatus> ACTIVE = EnumSet.of(
            ProjectCreationStatus.open,
            ProjectCreationStatus.upcoming,
            ProjectCreationStatus.approved,
            ProjectCreationStatus.onHold);

    private final StatusTransitionRecorder statusTransitionRecorder;

    public ProjectCompletion(StatusTransitionRecorder statusTransitionRecorder) {
        this.statusTransitionRecorder = statusTransitionRecorder;
    }

    /**
     * Whether every one of these tasks is complete. False for no tasks at all.
     *
     * @param tasks The project's tasks.
     * @return True only when there is at least one task and each is complete.
     */
    public static boolean allComplete(Collection<Task> tasks) {
        return tasks != null && !tasks.isEmpty() && tasks.stream().allMatch(ProjectCompletion::isComplete);
    }

    private static boolean isComplete(Task task) {
        return task.getStatus() == TaskStatus.completed
                || (task.getProgress() != null && task.getProgress() >= 100.0);
    }

    /**
     * Moves the project into or out of {@code completed} to match its tasks, recording the move.
     * Does not save the project; the caller does.
     *
     * @param project The project, after its tasks have changed.
     * @param tasks   Its tasks as they now stand.
     */
    public void reconcile(Project project, Collection<Task> tasks) {
        ProjectCreationStatus before = project.getStatus();
        boolean complete = allComplete(tasks);

        if (complete && (before == null || ACTIVE.contains(before))) {
            project.setStatus(ProjectCreationStatus.completed);
            project.setCompletedManually(false);
            record(project, before, "Every task of the project is complete");
        } else if (!complete && before == ProjectCreationStatus.completed
                && !Boolean.TRUE.equals(project.getCompletedManually())) {
            project.setStatus(ProjectCreationStatus.open);
            record(project, before, "A task of the project is no longer complete");
        }
    }

    private void record(Project project, ProjectCreationStatus before, String note) {
        statusTransitionRecorder.recordSystemChange(
                ProjectService.HISTORY_ENTITY_TYPE,
                project.getId(),
                project.getOrganization(),
                before != null ? before.name() : null,
                project.getStatus().name(),
                note);
    }
}
