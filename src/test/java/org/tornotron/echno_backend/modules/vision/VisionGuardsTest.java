package org.tornotron.echno_backend.modules.vision;

import static java.util.Map.entry;
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
import org.tornotron.echno_backend.modules.vision.web.VisionModelAdminController;
import org.tornotron.echno_backend.modules.vision.web.VisionModelController;
import org.tornotron.echno_backend.modules.vision.web.VisionWebController;

/**
 * Every computer vision handler an organization uses asks the access matrix for the vision action it
 * performs, and nothing else: reading the registry and the settings needs read, asking for a run needs
 * run, and changing the settings needs settings-manage. Writing the registry is the platform admin's,
 * for every organization at once, and stays off the matrix. A new handler fails here until it is
 * given its row.
 */
class VisionGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them; valued by area and action. */
    static final Map<String, String> PERMISSION_BY_HANDLER = Map.ofEntries(
            entry("VisionModelController#list", "vision:read"),
            entry("VisionModelController#version", "vision:read"),
            entry("VisionWebController#getSettings", "vision:read"),
            entry("VisionWebController#updateSettings", "vision:settings-manage"),
            entry("VisionWebController#queueRun", "vision:run"));

    @Test
    void everyOrganizationHandlerNeedsTheActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(VisionGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(PERMISSION_BY_HANDLER.keySet());
        PERMISSION_BY_HANDLER.forEach((handler, permission) -> {
            String[] parts = permission.split(":");
            assertThat(guards.get(handler)).as(handler)
                    .isEqualTo("@access.can('" + parts[0] + "', '" + parts[1] + "')");
        });
    }

    @Test
    void writingTheRegistryStaysThePlatformAdmins() {
        assertThat(handlersOf(VisionModelAdminController.class)).isNotEmpty()
                .allSatisfy(handler -> assertThat(handler.getAnnotation(PreAuthorize.class).value()).as(key(handler))
                        .isEqualTo("hasRole('platform-admin')"));
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(VisionModelController.class, VisionWebController.class)
                .flatMap(type -> handlersOf(type).stream())
                .toList();
    }

    private static List<Method> handlersOf(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
