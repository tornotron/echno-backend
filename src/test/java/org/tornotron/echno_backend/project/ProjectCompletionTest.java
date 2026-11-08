package org.tornotron.echno_backend.project;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.task.enums.TaskStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A project's completed status follows its tasks (ClickUp 14zdkkvzzwg): it completes when every
 * task is complete, goes back to open when one stops being complete, and a hand completion by a
 * system admin is left alone.
 */
class ProjectCompletionTest {

    private final StatusTransitionRecorder recorder = mock(StatusTransitionRecorder.class);
    private final ProjectCompletion completion = new ProjectCompletion(recorder);

    private static Task task(TaskStatus status, Double progress) {
        Task task = new Task();
        task.setStatus(status);
        task.setProgress(progress);
        return task;
    }

    private static Project project(ProjectCreationStatus status, boolean byHand) {
        Project project = new Project();
        project.setId(7L);
        project.setStatus(status);
        project.setCompletedManually(byHand);
        return project;
    }

    @Test
    void anOpenProjectCompletesWhenEveryTaskIsComplete() {
        Project project = project(ProjectCreationStatus.open, false);

        completion.reconcile(project, List.of(
                task(TaskStatus.completed, 80.0), task(TaskStatus.onGoing, 100.0)));

        assertThat(project.getStatus()).isEqualTo(ProjectCreationStatus.completed);
        assertThat(project.getCompletedManually()).isFalse();
        verify(recorder).recordSystemChange(eq("PROJECT"), eq(7L), any(), eq("open"), eq("completed"),
                anyString());
    }

    @Test
    void aProjectWithWorkLeftOrNoTasksDoesNotComplete() {
        Project withWorkLeft = project(ProjectCreationStatus.approved, false);
        completion.reconcile(withWorkLeft, List.of(
                task(TaskStatus.completed, 100.0), task(TaskStatus.onGoing, 40.0)));
        assertThat(withWorkLeft.getStatus()).isEqualTo(ProjectCreationStatus.approved);

        Project withNoTasks = project(ProjectCreationStatus.open, false);
        completion.reconcile(withNoTasks, List.of());
        assertThat(withNoTasks.getStatus()).isEqualTo(ProjectCreationStatus.open);

        verify(recorder, never()).recordSystemChange(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aProjectItsTasksCompletedReopensWhenATaskIsNoLongerComplete() {
        Project project = project(ProjectCreationStatus.completed, false);

        completion.reconcile(project, List.of(
                task(TaskStatus.completed, 100.0), task(TaskStatus.upcoming, 0.0)));

        assertThat(project.getStatus()).isEqualTo(ProjectCreationStatus.open);
    }

    @Test
    void aHandCompletionIsLeftAloneAndCancelledProjectsDoNotComplete() {
        Project byHand = project(ProjectCreationStatus.completed, true);
        completion.reconcile(byHand, List.of(task(TaskStatus.onGoing, 10.0)));
        assertThat(byHand.getStatus()).isEqualTo(ProjectCreationStatus.completed);

        Project cancelled = project(ProjectCreationStatus.cancelled, false);
        completion.reconcile(cancelled, List.of(task(TaskStatus.completed, 100.0)));
        assertThat(cancelled.getStatus()).isEqualTo(ProjectCreationStatus.cancelled);
    }
}
