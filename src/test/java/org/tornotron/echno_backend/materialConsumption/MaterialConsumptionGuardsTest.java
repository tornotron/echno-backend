package org.tornotron.echno_backend.materialConsumption;

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
 * Every material consumption handler, on the tenant API and its {@code /web} twin, asks the access
 * matrix for the material consumption action it performs, and nothing else: reads need read and
 * recording an issue needs create. A new handler fails here until it is given its row.
 */
class MaterialConsumptionGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them. */
    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            Map.entry("MaterialConsumptionController#createMaterialConsumption", "create"),
            Map.entry("MaterialConsumptionController#getMaterialConsumptionById", "read"),
            Map.entry("MaterialConsumptionController#getAllMaterialConsumptions", "read"),
            Map.entry("MaterialConsumptionController#getAllMaterialConsumptionsPaginated", "read"),
            Map.entry("MaterialConsumptionController#getConsumptionsByMaterial", "read"),
            Map.entry("MaterialConsumptionController#getConsumptionsByType", "read"),
            Map.entry("MaterialConsumptionController#getConsumptionsByDateRange", "read"),
            Map.entry("MaterialConsumptionController#getConsumptionsByTask", "read"),
            Map.entry("MaterialConsumptionControllerWeb#createMaterialConsumption", "create"),
            Map.entry("MaterialConsumptionControllerWeb#getMaterialConsumptionById", "read"),
            Map.entry("MaterialConsumptionControllerWeb#getAllMaterialConsumptions", "read"),
            Map.entry("MaterialConsumptionControllerWeb#getAllMaterialConsumptionsPaginated", "read"),
            Map.entry("MaterialConsumptionControllerWeb#getConsumptionsByMaterial", "read"),
            Map.entry("MaterialConsumptionControllerWeb#getConsumptionsByType", "read"),
            Map.entry("MaterialConsumptionControllerWeb#getConsumptionsByDateRange", "read"),
            Map.entry("MaterialConsumptionControllerWeb#getConsumptionsByTask", "read"));

    @Test
    void everyHandlerNeedsTheMaterialConsumptionActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(MaterialConsumptionGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(ACTION_BY_HANDLER.keySet());
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo("@access.can('material-consumption', '" + action + "')"));
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(MaterialConsumptionController.class, MaterialConsumptionControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
