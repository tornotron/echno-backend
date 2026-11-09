package org.tornotron.echno_backend.risk;


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
import org.tornotron.echno_backend.documentReversal.DocumentReversalController;
import org.tornotron.echno_backend.documentReversal.DocumentReversalControllerWeb;

/**
 * Every risk register and document reversal handler, on the tenant API and its {@code /web} twin,
 * asks the access matrix for the one action it performs, and nothing else. The risk register is the
 * compliance area; reversals are an area of their own, document-reversals, where asking for one is
 * create, withdrawing it is edit, and approving or rejecting it is approve. A new handler fails here
 * until it is given its row.
 */
class ComplianceGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them; valued by area and action. */
    static final Map<String, String> PERMISSION_BY_HANDLER = Map.ofEntries(
            Map.entry("ProjectRiskController#readRisks", "compliance:read"),
            Map.entry("ProjectRiskController#readRisk", "compliance:read"),
            Map.entry("ProjectRiskController#createRisk", "compliance:create"),
            Map.entry("ProjectRiskController#importRisks", "compliance:create"),
            Map.entry("ProjectRiskController#updateRisk", "compliance:edit"),
            Map.entry("ProjectRiskController#deleteRisk", "compliance:delete"),
            Map.entry("ProjectRiskControllerWeb#readRisks", "compliance:read"),
            Map.entry("ProjectRiskControllerWeb#readRisk", "compliance:read"),
            Map.entry("ProjectRiskControllerWeb#createRisk", "compliance:create"),
            Map.entry("ProjectRiskControllerWeb#importRisks", "compliance:create"),
            Map.entry("ProjectRiskControllerWeb#updateRisk", "compliance:edit"),
            Map.entry("ProjectRiskControllerWeb#deleteRisk", "compliance:delete"),
            Map.entry("DocumentReversalController#requestReversal", "document-reversals:create"),
            Map.entry("DocumentReversalController#listReversals", "document-reversals:read"),
            Map.entry("DocumentReversalController#listReversalsByDocument", "document-reversals:read"),
            Map.entry("DocumentReversalController#eligibility", "document-reversals:read"),
            Map.entry("DocumentReversalController#getReversal", "document-reversals:read"),
            Map.entry("DocumentReversalController#approveReversal", "document-reversals:approve"),
            Map.entry("DocumentReversalController#rejectReversal", "document-reversals:approve"),
            Map.entry("DocumentReversalController#cancelReversal", "document-reversals:edit"),
            Map.entry("DocumentReversalControllerWeb#requestReversal", "document-reversals:create"),
            Map.entry("DocumentReversalControllerWeb#listReversals", "document-reversals:read"),
            Map.entry("DocumentReversalControllerWeb#listReversalsByDocument", "document-reversals:read"),
            Map.entry("DocumentReversalControllerWeb#eligibility", "document-reversals:read"),
            Map.entry("DocumentReversalControllerWeb#getReversal", "document-reversals:read"),
            Map.entry("DocumentReversalControllerWeb#approveReversal", "document-reversals:approve"),
            Map.entry("DocumentReversalControllerWeb#rejectReversal", "document-reversals:approve"),
            Map.entry("DocumentReversalControllerWeb#cancelReversal", "document-reversals:edit"));

    @Test
    void everyHandlerNeedsTheActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(ComplianceGuardsTest::key,
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
        return Stream.of(ProjectRiskController.class, ProjectRiskControllerWeb.class,
                        DocumentReversalController.class, DocumentReversalControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
