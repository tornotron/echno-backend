package org.tornotron.echno_backend.project.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectProgressLookup;
import org.tornotron.echno_backend.project.ProjectProgressTotals;
import org.tornotron.echno_backend.project.dto.ProjectSimpleDto;
import org.tornotron.echno_backend.project.dto.ProjectSummaryDto;
import org.tornotron.echno_backend.task.Task;

/**
 * Pins what {@code progress} means on the shape a create and an update reply with.
 *
 * <p>{@link ProjectMapper#toSimpleDto} used to map the field by name from the entity's own
 * {@code progress} column. Nothing in the codebase writes that column, so the field came back
 * null on every create and every partial update, and a client that reads a number where the
 * backend sent nothing gets zero: {@code echno-core} parses the response with
 * {@code progress: Number(raw.progress ?? 0)} and merges the parsed object over the cached
 * project detail, where {@code progress} is a scalar and is overwritten rather than preserved.
 * Editing a project therefore dropped the figure on screen to 0% until the refetch landed.
 *
 * <p>The mapper derives it now, so the same field name means the same thing on all three project
 * shapes. These tests are on the simple DTO because that is the one that was wrong, and they check
 * it against the summary projection, which computes the same average a different way.
 *
 * <p>Plain unit tests: {@link ProjectMapper#toSimpleDto} maps no collections, so the generated
 * mapper needs none of the nested mappers it is wired with in the container.
 */
class ProjectMapperProgressTest {

    private final ProjectMapper mapper = new ProjectMapperImpl();

    @Test
    void progressOfTheSimpleDto_isTheAverageOfTheTasks() {
        Project project = projectWithTaskProgress(20.0, 40.0, 90.0);

        ProjectSimpleDto dto = mapper.toSimpleDto(project);

        assertThat(dto.getProgress())
                .as("a create or an update reports the same derived figure the read shape does")
                .isEqualTo(50.0);
    }

    @Test
    void progressOfAProjectWithNoTasks_isZeroRatherThanNull() {
        Project project = projectWithTaskProgress();

        ProjectSimpleDto dto = mapper.toSimpleDto(project);

        assertThat(dto.getProgress())
                .as("a project that has just been created has nothing to average, and zero is the "
                        + "honest answer; null becomes zero in the client anyway, silently")
                .isEqualTo(0.0);
    }

    @Test
    void progressOfAProjectWhoseTasksAreNotLoaded_isZeroRatherThanNull() {
        Project project = new Project();
        project.setId(7L);
        project.setTasks(null);

        ProjectSimpleDto dto = mapper.toSimpleDto(project);

        assertThat(dto.getProgress()).isEqualTo(0.0);
    }

    @Test
    void theEntitysOwnProgressColumn_isNotRead() {
        Project project = projectWithTaskProgress(20.0, 40.0, 90.0);
        project.setProgress(99.0);

        ProjectSimpleDto dto = mapper.toSimpleDto(project);

        assertThat(dto.getProgress())
                .as("the column is dead: nothing writes it, so a value left in it must not reach "
                        + "a response in preference to the derived figure")
                .isEqualTo(50.0);
    }

    @Test
    void tasksCarryingNoProgress_areIgnoredNotCountedAsZero() {
        Project project = projectWithTaskProgress(60.0, null, 80.0);

        ProjectSimpleDto dto = mapper.toSimpleDto(project);

        assertThat(dto.getProgress()).isEqualTo(70.0);
    }

    @Test
    void theSimpleDtoAndTheListProjection_reportTheSameFigure() {
        Project project = projectWithTaskProgress(20.0, 40.0, 90.0);
        ProjectProgressLookup lookup =
                ProjectProgressLookup.of(List.of(new ProjectProgressTotals(project.getId(), 50.0)));

        ProjectSimpleDto simple = mapper.toSimpleDto(project);
        ProjectSummaryDto summary = mapper.toSummaryDto(project, lookup);

        assertThat(simple.getProgress())
                .as("the field means one thing across the shapes, whichever endpoint answered")
                .isEqualTo(summary.getProgress());
    }

    private Project projectWithTaskProgress(Double... progressValues) {
        Project project = new Project();
        project.setId(7L);
        List<Task> tasks = new ArrayList<>();
        for (Double value : progressValues) {
            Task task = new Task();
            task.setProgress(value);
            tasks.add(task);
        }
        project.setTasks(tasks);
        return project;
    }
}
