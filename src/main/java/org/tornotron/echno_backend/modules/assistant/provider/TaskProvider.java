package org.tornotron.echno_backend.modules.assistant.provider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.assistant.api.AssistantProvider;
import org.tornotron.echno_backend.modules.assistant.api.CostProfile;
import org.tornotron.echno_backend.modules.assistant.api.EvidenceUnit;
import org.tornotron.echno_backend.modules.assistant.api.FieldSpec;
import org.tornotron.echno_backend.modules.assistant.api.ProviderDescriptor;
import org.tornotron.echno_backend.modules.assistant.api.ProviderResult;
import org.tornotron.echno_backend.modules.assistant.api.Question;
import org.tornotron.echno_backend.modules.assistant.api.Scope;
import org.tornotron.echno_backend.modules.assistant.api.SourceRef;
import org.tornotron.echno_backend.modules.assistant.api.Subject;
import org.tornotron.echno_backend.modules.assistant.time.AssistantClock;
import org.tornotron.echno_backend.task.TaskService;
import org.tornotron.echno_backend.task.dto.TaskDto;
import org.tornotron.echno_backend.task.enums.TaskStatus;

/**
 * The tasks of one project as they stand now: one summary unit with the totals, then one unit per
 * task with its status, progress, planned dates and whether it is overdue.
 *
 * <p>Reads through {@link TaskService#getTasksByProjectId}, the service behind the project task
 * list on the web controller, so the tenant filter applies by construction. That list is guarded on
 * the controller, not on the service, so {@link #retrieve} carries the same guard,
 * {@link #READ_GUARD}, and {@code ProviderGuardParityTest} fails if the two ever differ.
 *
 * <p>The plan a task is measured against is its own planned start and end date. There is no
 * planned-progress figure on a task, so "behind plan" is only what the dates say: a task is
 * overdue when its planned end has passed and it is not completed. Whether anything richer is
 * wanted is a question for the ticket, and this provider does not guess at it.
 *
 * <p>The summary is computed over every task, in Java, before the list is cut, so a count such as
 * "how many are overdue" is right even for a project with more tasks than the list holds. Names
 * of assignees are not returned.
 */
@Slf4j
@Component
public class TaskProvider implements AssistantProvider {

    public static final String ID = "task";

    /** The guard on {@code GET /projectId/{projectId}} of the web task controller. */
    public static final String READ_GUARD = "@orgSecurity.isMemberOfCurrentTenant()";

    // The service returns a project's whole task list and has no page to walk, so the list in the
    // evidence is cut here; the summary still counts everything.
    // ponytail: the cut is by relevance, overdue first; page the read if a project outgrows this.
    static final int MAX_LISTED = 100;

    private static final String STATUSES = Arrays.stream(TaskStatus.values())
            .map(Enum::name).collect(Collectors.joining(", "));

    private static final ProviderDescriptor DESCRIPTOR = new ProviderDescriptor(
            ID,
            "The tasks of one project as they stand now: how many there are in each status, how many are "
                    + "overdue, the average progress, and for each task its status, progress, planned start and "
                    + "end and whether it is overdue. It needs a project and no period. It does not name the "
                    + "people assigned.",
            List.of(
                    new FieldSpec("taskCount", "tasks", "All tasks of the project, whatever their status."),
                    new FieldSpec("tasksListed", "tasks",
                            "How many of them are listed one by one; fewer than taskCount when the list was cut."),
                    new FieldSpec("statusCounts", "tasks per status",
                            "Tasks per status, named as the task module names them (" + STATUSES
                                    + "); a status with no tasks is left out."),
                    new FieldSpec("overdueCount", "tasks",
                            "Tasks whose planned end has passed and are not completed."),
                    new FieldSpec("averageProgress", "percent, 0 to 100",
                            "The mean progress over the tasks that report one, to one decimal place."),
                    new FieldSpec("taskId", "id", "The task's id."),
                    new FieldSpec("title", "text", "The task's title."),
                    new FieldSpec("status", "status name", "The task's status."),
                    new FieldSpec("progress", "percent, 0 to 100", "Completion of the task; left out when none is recorded."),
                    new FieldSpec("startDate", "ISO-8601 date-time", "Planned start; left out when none is set."),
                    new FieldSpec("endDate", "ISO-8601 date-time", "Planned end; left out when none is set."),
                    new FieldSpec("overdue", "true or false", "True when the planned end has passed and the task is not completed.")),
            Set.of(Subject.TASK),
            false,
            false,
            // An estimate: one read of the project's tasks.
            new CostProfile(400, 0));

    private final TaskService taskService;
    private final Clock clock;

    public TaskProvider(TaskService taskService, @AssistantClock Clock clock) {
        this.taskService = taskService;
        this.clock = clock;
    }

    @Override
    public ProviderDescriptor describe() {
        return DESCRIPTOR;
    }

    @Override
    @PreAuthorize(READ_GUARD)
    public ProviderResult retrieve(Question question, Scope scope) {
        if (scope.projectId() == null) {
            return new ProviderResult.Unavailable("Tasks are read for one project, and none was given.");
        }
        List<TaskDto> tasks;
        try {
            tasks = taskService.getTasksByProjectId(scope.projectId());
        } catch (AccessDeniedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Tasks could not be read for project {}", scope.projectId(), e);
            return new ProviderResult.Unavailable("Tasks for project " + scope.projectId() + " could not be read.");
        }
        if (tasks.isEmpty()) {
            return new ProviderResult.Empty("tasks of project " + scope.projectId());
        }

        LocalDateTime now = LocalDateTime.now(clock);
        List<EvidenceUnit> units = new java.util.ArrayList<>();
        List<TaskDto> listed = tasks.stream()
                .sorted(Comparator.comparingDouble((TaskDto t) -> relevance(t, now)).reversed()
                        .thenComparing(TaskDto::getId))
                .limit(MAX_LISTED)
                .toList();
        units.add(summary(scope.projectId(), tasks, listed.size(), now));
        for (TaskDto task : listed) {
            units.add(unit(scope.projectId(), task, now));
        }
        return new ProviderResult.Evidence(units);
    }

    private static EvidenceUnit summary(Long projectId, List<TaskDto> tasks, int listed, LocalDateTime now) {
        Map<TaskStatus, Integer> byStatus = new EnumMap<>(TaskStatus.class);
        tasks.forEach(t -> byStatus.merge(t.getStatus(), 1, Integer::sum));
        Map<String, Object> counts = new LinkedHashMap<>();
        byStatus.forEach((status, count) -> counts.put(status.name(), count));

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("taskCount", tasks.size());
        values.put("tasksListed", listed);
        values.put("statusCounts", Collections.unmodifiableMap(counts));
        values.put("overdueCount", tasks.stream().filter(t -> overdue(t, now)).count());
        List<Double> progress = tasks.stream().map(TaskDto::getProgress).filter(p -> p != null).toList();
        if (!progress.isEmpty()) {
            double mean = progress.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            values.put("averageProgress", BigDecimal.valueOf(mean).setScale(1, RoundingMode.HALF_UP).doubleValue());
        }
        return new EvidenceUnit("task-summary:project-" + projectId, ID, "task-summary", values,
                SourceRef.entity("project-tasks", Long.toString(projectId)), 1.0);
    }

    private static EvidenceUnit unit(Long projectId, TaskDto task, LocalDateTime now) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("taskId", task.getId());
        values.put("title", task.getTitle());
        values.put("status", task.getStatus().name());
        if (task.getProgress() != null) {
            values.put("progress", task.getProgress());
        }
        if (task.getStartDate() != null) {
            values.put("startDate", task.getStartDate().toString());
        }
        if (task.getEndDate() != null) {
            values.put("endDate", task.getEndDate().toString());
        }
        values.put("overdue", overdue(task, now));
        return new EvidenceUnit("task:" + task.getId(), ID, "task", values,
                SourceRef.entity("task", Long.toString(task.getId())), relevance(task, now));
    }

    private static boolean overdue(TaskDto task, LocalDateTime now) {
        return task.getEndDate() != null && task.getEndDate().isBefore(now) && task.getStatus() != TaskStatus.completed;
    }

    /** Overdue work first, then work under way, then what is waiting, then what is finished. */
    private static double relevance(TaskDto task, LocalDateTime now) {
        if (overdue(task, now)) {
            return 0.9;
        }
        return switch (task.getStatus()) {
            case onGoing -> 0.8;
            case upcoming -> 0.6;
            case onHold -> 0.5;
            case completed -> 0.3;
        };
    }
}
