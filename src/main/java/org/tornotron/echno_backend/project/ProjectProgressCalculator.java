package org.tornotron.echno_backend.project;

import java.util.List;
import java.util.Objects;

import org.tornotron.echno_backend.task.Task;

/** Average of a project's task progress values (0.0 when there are no tasks with a progress). */
public final class ProjectProgressCalculator {

    private ProjectProgressCalculator() {
    }

    public static Double calculate(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return 0.0;
        }
        List<Double> values = tasks.stream()
                .map(Task::getProgress)
                .filter(Objects::nonNull)
                .toList();
        if (values.isEmpty()) {
            return 0.0;
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }
}
