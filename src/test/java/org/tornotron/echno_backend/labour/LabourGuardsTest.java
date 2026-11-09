package org.tornotron.echno_backend.labour;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Every labour handler asks the access matrix for the labour action it performs, and nothing else:
 * the reads need read and each write needs the create, edit or delete it makes.
 * The tenant API twin, {@link LabourController}, inherits these handlers and their guards. A new handler
 * fails here until it is given its row.
 */
class LabourGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them. */
    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            Map.entry("LabourControllerWeb#createLabour", "create"),
            Map.entry("LabourControllerWeb#getAllLabours", "read"),
            Map.entry("LabourControllerWeb#getALabour", "read"),
            Map.entry("LabourControllerWeb#partialUpdateALabour", "edit"),
            Map.entry("LabourControllerWeb#deleteALabour", "delete"));

    @Test
    void everyHandlerNeedsTheLabourActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(LabourGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(ACTION_BY_HANDLER.keySet());
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo("@access.can('labour', '" + action + "')"));
    }

    @Test
    void theTenantApiTwinDeclaresNoHandlerOfItsOwn() {
        assertThat(Arrays.stream(LabourController.class.getDeclaredMethods())
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class))))
                .isEmpty();
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Arrays.stream(LabourControllerWeb.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
