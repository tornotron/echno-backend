package org.tornotron.echno_backend.attendance;

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
 * Pins the access matrix action each attendance regularisation handler needs, on the tenant API
 * and its {@code /web} twin.
 */
class RegularisationGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them; valued by area and action. */
    static final Map<String, String> PERMISSION_BY_HANDLER = Map.ofEntries(
            Map.entry("AttendanceRegularizationController#submitRequest", "leave-regularisation:create"),
            Map.entry("AttendanceRegularizationController#submitByDate", "leave-regularisation:create"),
            Map.entry("AttendanceRegularizationController#calendar", "leave-regularisation:read"),
            Map.entry("AttendanceRegularizationController#process", "leave-regularisation:edit"),
            Map.entry("AttendanceRegularizationController#getPending", "leave-regularisation:read"),
            Map.entry("AttendanceRegularizationController#getById", "leave-regularisation:read"),
            Map.entry("AttendanceRegularizationControllerWeb#submitRequest", "leave-regularisation:create"),
            Map.entry("AttendanceRegularizationControllerWeb#submitByDate", "leave-regularisation:create"),
            Map.entry("AttendanceRegularizationControllerWeb#calendar", "leave-regularisation:read"),
            Map.entry("AttendanceRegularizationControllerWeb#process", "leave-regularisation:edit"),
            Map.entry("AttendanceRegularizationControllerWeb#getPending", "leave-regularisation:read"),
            Map.entry("AttendanceRegularizationControllerWeb#list", "leave-regularisation:read"),
            Map.entry("AttendanceRegularizationControllerWeb#getById", "leave-regularisation:read"));

    @Test
    void everyHandlerNeedsTheActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(RegularisationGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(PERMISSION_BY_HANDLER.keySet());
        PERMISSION_BY_HANDLER.forEach((handler, permission) -> {
            String[] parts = permission.split(":");
            assertThat(guards.get(handler)).as(handler)
                    .isEqualTo("@access.can('" + parts[0] + "', '" + parts[1] + "')");
        });
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(AttendanceRegularizationController.class, AttendanceRegularizationControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
