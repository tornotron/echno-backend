package org.tornotron.echno_backend.wbs;

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
 * Every WBS handler, on the tenant API and its {@code /web} twin, asks the access matrix for the wbs
 * action it performs, and nothing else: the reads need read, adding elements needs create, moving,
 * recalculating, changing an element and its dependencies need edit, and removing an element needs
 * delete. A new handler fails here until it is given its row.
 */
class WbsGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them. */
    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            Map.entry("WbsElementController#createWbsElement", "create"),
            Map.entry("WbsElementController#bulkCreateWbsElements", "create"),
            Map.entry("WbsElementController#getWbsTree", "read"),
            Map.entry("WbsElementController#getWbsFlatList", "read"),
            Map.entry("WbsElementController#getWbsElementById", "read"),
            Map.entry("WbsElementController#updateWbsElement", "edit"),
            Map.entry("WbsElementController#deleteWbsElement", "delete"),
            Map.entry("WbsElementController#moveWbsElement", "edit"),
            Map.entry("WbsElementController#getLeafElements", "read"),
            Map.entry("WbsElementController#recalculateElement", "edit"),
            Map.entry("WbsElementController#getSchedule", "read"),
            Map.entry("WbsElementController#addDependency", "edit"),
            Map.entry("WbsElementController#removeDependency", "edit"),
            Map.entry("WbsElementControllerWeb#createWbsElement", "create"),
            Map.entry("WbsElementControllerWeb#bulkCreateWbsElements", "create"),
            Map.entry("WbsElementControllerWeb#getWbsTree", "read"),
            Map.entry("WbsElementControllerWeb#getWbsFlatList", "read"),
            Map.entry("WbsElementControllerWeb#getWbsElementById", "read"),
            Map.entry("WbsElementControllerWeb#updateWbsElement", "edit"),
            Map.entry("WbsElementControllerWeb#deleteWbsElement", "delete"),
            Map.entry("WbsElementControllerWeb#moveWbsElement", "edit"),
            Map.entry("WbsElementControllerWeb#getLeafElements", "read"),
            Map.entry("WbsElementControllerWeb#recalculateElement", "edit"),
            Map.entry("WbsElementControllerWeb#getSchedule", "read"),
            Map.entry("WbsElementControllerWeb#addDependency", "edit"),
            Map.entry("WbsElementControllerWeb#removeDependency", "edit"));

    @Test
    void everyHandlerNeedsTheWbsActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(WbsGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(ACTION_BY_HANDLER.keySet());
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo("@access.can('wbs', '" + action + "')"));
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(WbsElementController.class, WbsElementControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
