package org.tornotron.echno_backend.subcontract;

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
 * Every subcontract handler asks the access matrix for the construction action it performs, and
 * nothing else: the reads need read and each write needs the create, edit or delete it makes.
 * The tenant API twin, {@link SubContractController}, inherits these handlers and their guards. A new handler
 * fails here until it is given its row.
 */
class SubContractGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them. */
    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            Map.entry("SubContractControllerWeb#readAllSubContracts", "read"),
            Map.entry("SubContractControllerWeb#readAllSubContractsPaginated", "read"),
            Map.entry("SubContractControllerWeb#readASubContract", "read"),
            Map.entry("SubContractControllerWeb#createSubContract", "create"),
            Map.entry("SubContractControllerWeb#updateSubContract", "edit"),
            Map.entry("SubContractControllerWeb#deleteSubContract", "delete"));

    @Test
    void everyHandlerNeedsTheConstructionActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(SubContractGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(ACTION_BY_HANDLER.keySet());
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo("@access.can('construction', '" + action + "')"));
    }

    @Test
    void theTenantApiTwinDeclaresNoHandlerOfItsOwn() {
        assertThat(Arrays.stream(SubContractController.class.getDeclaredMethods())
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class))))
                .isEmpty();
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Arrays.stream(SubContractControllerWeb.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
