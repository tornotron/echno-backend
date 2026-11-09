package org.tornotron.echno_backend.modules.toolboxtalks;

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
import org.tornotron.echno_backend.modules.toolboxtalks.web.ToolboxTalksController;
import org.tornotron.echno_backend.modules.toolboxtalks.web.ToolboxTalksControllerWeb;

/**
 * Every toolbox talk handler, on the tenant API and its {@code /web} twin, asks the access matrix
 * for the toolbox-talks action it performs, and nothing else: the reads and the PDF need read,
 * scheduling a talk needs create, and changing one, its attendees, its record and its photos needs
 * edit. A new handler fails here until it is given its row.
 */
class ToolboxTalksGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them; valued by area and action. */
    static final Map<String, String> PERMISSION_BY_HANDLER = Map.ofEntries(
            Map.entry("ToolboxTalksController#list", "toolbox-talks:read"),
            Map.entry("ToolboxTalksController#get", "toolbox-talks:read"),
            Map.entry("ToolboxTalksController#create", "toolbox-talks:create"),
            Map.entry("ToolboxTalksController#update", "toolbox-talks:edit"),
            Map.entry("ToolboxTalksController#addAttendees", "toolbox-talks:edit"),
            Map.entry("ToolboxTalksController#removeAttendee", "toolbox-talks:edit"),
            Map.entry("ToolboxTalksController#record", "toolbox-talks:edit"),
            Map.entry("ToolboxTalksController#listPhotos", "toolbox-talks:read"),
            Map.entry("ToolboxTalksController#presignPhotos", "toolbox-talks:edit"),
            Map.entry("ToolboxTalksController#registerPhotos", "toolbox-talks:edit"),
            Map.entry("ToolboxTalksController#exportPdf", "toolbox-talks:read"),
            Map.entry("ToolboxTalksControllerWeb#list", "toolbox-talks:read"),
            Map.entry("ToolboxTalksControllerWeb#get", "toolbox-talks:read"),
            Map.entry("ToolboxTalksControllerWeb#create", "toolbox-talks:create"),
            Map.entry("ToolboxTalksControllerWeb#update", "toolbox-talks:edit"),
            Map.entry("ToolboxTalksControllerWeb#addAttendees", "toolbox-talks:edit"),
            Map.entry("ToolboxTalksControllerWeb#removeAttendee", "toolbox-talks:edit"),
            Map.entry("ToolboxTalksControllerWeb#record", "toolbox-talks:edit"),
            Map.entry("ToolboxTalksControllerWeb#listPhotos", "toolbox-talks:read"),
            Map.entry("ToolboxTalksControllerWeb#presignPhotos", "toolbox-talks:edit"),
            Map.entry("ToolboxTalksControllerWeb#registerPhotos", "toolbox-talks:edit"),
            Map.entry("ToolboxTalksControllerWeb#exportPdf", "toolbox-talks:read"));

    @Test
    void everyHandlerNeedsTheActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(ToolboxTalksGuardsTest::key,
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
        return Stream.of(ToolboxTalksController.class, ToolboxTalksControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
