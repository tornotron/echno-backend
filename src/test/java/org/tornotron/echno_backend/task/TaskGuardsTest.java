package org.tornotron.echno_backend.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Every task handler, on the tenant API and its {@code /web} twin, asks the access matrix for the
 * tasks action it performs, and nothing else: reads need read, and creating, changing and deleting a
 * task need create, edit and delete. A new handler fails here until it is given its row.
 */
class TaskGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them. */
    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            Map.entry("TaskController#createTask", "create"),
            Map.entry("TaskController#readAllTasks", "read"),
            Map.entry("TaskController#readATask", "read"),
            Map.entry("TaskController#batchUpdateTasks", "edit"),
            Map.entry("TaskController#deleteATask", "delete"),
            Map.entry("TaskControllerWeb#createTask", "create"),
            Map.entry("TaskControllerWeb#readAllTasks", "read"),
            Map.entry("TaskControllerWeb#readAllTasksPaginated", "read"),
            Map.entry("TaskControllerWeb#readAllTasksForProject", "read"),
            Map.entry("TaskControllerWeb#readATask", "read"),
            Map.entry("TaskControllerWeb#partialUpdateATask", "edit"),
            Map.entry("TaskControllerWeb#batchUpdateTasks", "edit"),
            Map.entry("TaskControllerWeb#deleteATask", "delete"));

    @Test
    void everyHandlerNeedsTheTasksActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(TaskGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(ACTION_BY_HANDLER.keySet());
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo("@access.can('tasks', '" + action + "')"));
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(TaskController.class, TaskControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
