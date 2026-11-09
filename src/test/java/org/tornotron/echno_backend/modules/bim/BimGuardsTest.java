package org.tornotron.echno_backend.modules.bim;

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
import org.tornotron.echno_backend.modules.bim.web.BimModelController;

/**
 * Every BIM handler asks the access matrix for the bim action it performs, and nothing else: the reads
 * need read, confirming a version's hierarchy needs approve, and every other change (a model, an upload,
 * an import job, a regenerated proposal, an element merge) needs create. A new handler fails here until
 * it is given its row.
 */
class BimGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them. */
    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            Map.entry("BimModelController#listForProject", "read"),
            Map.entry("BimModelController#create", "create"),
            Map.entry("BimModelController#get", "read"),
            Map.entry("BimModelController#getVersion", "read"),
            Map.entry("BimModelController#presignSource", "create"),
            Map.entry("BimModelController#registerSource", "create"),
            Map.entry("BimModelController#tiles", "read"),
            Map.entry("BimModelController#listElements", "read"),
            Map.entry("BimModelController#getElement", "read"),
            Map.entry("BimModelController#getElementByGlobalId", "read"),
            Map.entry("BimModelController#merge", "create"),
            Map.entry("BimModelController#getHierarchyProposal", "read"),
            Map.entry("BimModelController#regenerateHierarchyProposal", "create"),
            Map.entry("BimModelController#confirmHierarchy", "approve"),
            Map.entry("BimModelController#listJobs", "read"),
            Map.entry("BimModelController#enqueue", "create"),
            Map.entry("BimModelController#getJob", "read"));

    @Test
    void everyHandlerNeedsTheBimActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(BimGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(ACTION_BY_HANDLER.keySet());
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo("@access.can('bim', '" + action + "')"));
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Arrays.stream(BimModelController.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
