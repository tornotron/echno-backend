package org.tornotron.echno_backend.modules.assistant.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.modules.assistant.api.EvidenceUnit;
import org.tornotron.echno_backend.modules.assistant.api.FieldSpec;
import org.tornotron.echno_backend.modules.assistant.api.ProviderResult;
import org.tornotron.echno_backend.modules.assistant.api.Question;
import org.tornotron.echno_backend.modules.assistant.api.Scope;
import org.tornotron.echno_backend.modules.assistant.api.Subject;
import org.tornotron.echno_backend.task.TaskService;
import org.tornotron.echno_backend.task.dto.TaskDto;
import org.tornotron.echno_backend.task.enums.TaskStatus;

/** The task provider with the task service mocked: what it declares, what it returns, and the Empty versus Unavailable split. */
class TaskProviderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T04:00:00Z"), ZoneId.of("Asia/Kolkata"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 7, 9, 30);
    private static final Question QUESTION = new Question("which tasks are late?");
    private static final Scope SCOPE = new Scope(42L, null, null, null);

    private final TaskService service = mock(TaskService.class);
    private final TaskProvider provider = new TaskProvider(service, CLOCK);

    @Test
    void describesItselfWithFieldNamesAndUnits() {
        var descriptor = provider.describe();

        assertThat(descriptor.id()).isEqualTo("task");
        assertThat(descriptor.subjects()).containsExactly(Subject.TASK);
        assertThat(descriptor.needsPeriod()).isFalse();
        assertThat(descriptor.carriesPersonalData()).isFalse();
        assertThat(descriptor.fields()).allSatisfy(f -> {
            assertThat(f.name()).isNotBlank();
            assertThat(f.unit()).isNotBlank();
        });
        assertThat(descriptor.fields()).extracting(FieldSpec::name)
                .contains("taskCount", "overdueCount", "averageProgress", "status", "progress", "endDate", "overdue");
    }

    @Test
    void theStatusFieldNamesEveryStatusTheTaskModuleHasSoItCannotDrift() {
        String meaning = provider.describe().fields().stream()
                .filter(f -> f.name().equals("statusCounts")).findFirst().orElseThrow().meaning();

        for (TaskStatus status : TaskStatus.values()) {
            assertThat(meaning).contains(status.name());
        }
    }

    @Test
    void everyFieldItReturnsIsOneItDeclared() {
        when(service.getTasksByProjectId(42L)).thenReturn(List.of(
                task(1L, TaskStatus.onGoing, 50.0, NOW.minusDays(1)), task(2L, TaskStatus.upcoming, null, null)));

        Set<String> declared = provider.describe().fields().stream().map(FieldSpec::name)
                .collect(java.util.stream.Collectors.toSet());

        for (EvidenceUnit unit : units(provider.retrieve(QUESTION, SCOPE))) {
            assertThat(declared).containsAll(unit.values().keySet());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsTheSummaryFirstComputedOverEveryTaskInJava() {
        when(service.getTasksByProjectId(42L)).thenReturn(List.of(
                task(1L, TaskStatus.onGoing, 50.0, NOW.minusDays(1)),     // overdue
                task(2L, TaskStatus.completed, 100.0, NOW.minusDays(5)),  // past its end but done
                task(3L, TaskStatus.upcoming, 0.0, NOW.plusDays(5))));

        EvidenceUnit summary = units(provider.retrieve(QUESTION, SCOPE)).stream()
                .filter(u -> u.kind().equals("task-summary")).findFirst().orElseThrow();

        assertThat(summary.id()).isEqualTo("task-summary:project-42");
        assertThat(summary.values()).containsEntry("taskCount", 3)
                .containsEntry("overdueCount", 1L)
                .containsEntry("averageProgress", 50.0);
        assertThat((Map<String, Object>) summary.values().get("statusCounts"))
                .containsEntry("onGoing", 1).containsEntry("completed", 1).containsEntry("upcoming", 1);
    }

    @Test
    void aCompletedTaskPastItsEndIsNotOverdueButAnOpenOneIs() {
        when(service.getTasksByProjectId(42L)).thenReturn(List.of(
                task(1L, TaskStatus.completed, 100.0, NOW.minusDays(5)),
                task(2L, TaskStatus.onHold, 10.0, NOW.minusHours(1))));

        List<EvidenceUnit> units = units(provider.retrieve(QUESTION, SCOPE));

        assertThat(byId(units, "task:1").values()).containsEntry("overdue", false);
        assertThat(byId(units, "task:2").values()).containsEntry("overdue", true);
    }

    @Test
    void overdueWorkIsMostRelevantThenWorkUnderWayThenWhatIsFinished() {
        when(service.getTasksByProjectId(42L)).thenReturn(List.of(
                task(1L, TaskStatus.completed, 100.0, NOW.minusDays(5)),
                task(2L, TaskStatus.upcoming, 0.0, NOW.plusDays(5)),
                task(3L, TaskStatus.onGoing, 20.0, NOW.minusDays(1))));

        List<EvidenceUnit> units = units(provider.retrieve(QUESTION, SCOPE));

        assertThat(byId(units, "task:3").relevance()).isGreaterThan(byId(units, "task:2").relevance());
        assertThat(byId(units, "task:2").relevance()).isGreaterThan(byId(units, "task:1").relevance());
    }

    @Test
    void aTaskWithNoProgressOrDatesCarriesNoValueForThemRatherThanANull() {
        when(service.getTasksByProjectId(42L)).thenReturn(List.of(task(1L, TaskStatus.upcoming, null, null)));

        EvidenceUnit unit = byId(units(provider.retrieve(QUESTION, SCOPE)), "task:1");

        assertThat(unit.values()).doesNotContainKeys("progress", "startDate", "endDate");
        assertThat(unit.values()).containsEntry("overdue", false);
    }

    @Test
    void aBigProjectIsListedUpToTheCapButTheSummaryStillCountsEveryTask() {
        List<TaskDto> tasks = new ArrayList<>();
        for (long id = 1; id <= TaskProvider.MAX_LISTED + 25; id++) {
            tasks.add(task(id, TaskStatus.onGoing, 10.0, NOW.minusDays(1)));
        }
        when(service.getTasksByProjectId(42L)).thenReturn(tasks);

        List<EvidenceUnit> units = units(provider.retrieve(QUESTION, SCOPE));
        EvidenceUnit summary = byId(units, "task-summary:project-42");

        assertThat(units).hasSize(TaskProvider.MAX_LISTED + 1);
        assertThat(summary.values()).containsEntry("taskCount", TaskProvider.MAX_LISTED + 25)
                .containsEntry("tasksListed", TaskProvider.MAX_LISTED)
                .containsEntry("overdueCount", (long) (TaskProvider.MAX_LISTED + 25));
    }

    @Test
    void aProjectWithNoTasksIsEmptyAndSaysWhatWasLookedFor() {
        when(service.getTasksByProjectId(42L)).thenReturn(List.of());

        var result = provider.retrieve(QUESTION, SCOPE);

        assertThat(result).isInstanceOfSatisfying(ProviderResult.Empty.class,
                empty -> assertThat(empty.lookedFor()).contains("tasks").contains("42"));
    }

    @Test
    void aFailureToReadIsUnavailableNotEmptyAndLeaksNoInternals() {
        when(service.getTasksByProjectId(42L)).thenThrow(new IllegalStateException("jdbc:cockroach://secret-host failed"));

        var result = provider.retrieve(QUESTION, SCOPE);

        assertThat(result).isInstanceOfSatisfying(ProviderResult.Unavailable.class, unavailable -> {
            assertThat(unavailable.reason()).contains("42");
            assertThat(unavailable.reason()).doesNotContain("jdbc").doesNotContain("secret-host");
        });
    }

    @Test
    void aRefusalStaysARefusalInsteadOfBecomingACouldNotBeRead() {
        when(service.getTasksByProjectId(42L)).thenThrow(new AccessDeniedException("no"));

        assertThatThrownBy(() -> provider.retrieve(QUESTION, SCOPE)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void noProjectIsUnavailableAndNeverReachesTheService() {
        var result = provider.retrieve(QUESTION, new Scope(null, null, null, null));

        assertThat(result).isInstanceOf(ProviderResult.Unavailable.class);
        verify(service, never()).getTasksByProjectId(org.mockito.ArgumentMatchers.any());
    }

    private static List<EvidenceUnit> units(ProviderResult result) {
        assertThat(result).isInstanceOf(ProviderResult.Evidence.class);
        return ((ProviderResult.Evidence) result).units();
    }

    private static EvidenceUnit byId(List<EvidenceUnit> units, String id) {
        return units.stream().filter(u -> u.id().equals(id)).findFirst().orElseThrow();
    }

    private static TaskDto task(Long id, TaskStatus status, Double progress, LocalDateTime end) {
        TaskDto task = new TaskDto();
        task.setId(id);
        task.setTitle("Task " + id);
        task.setStatus(status);
        task.setProgress(progress);
        task.setEndDate(end);
        return task;
    }
}
