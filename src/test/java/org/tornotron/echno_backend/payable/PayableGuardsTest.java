package org.tornotron.echno_backend.payable;

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
 * Every payable handler, on the tenant API and its {@code /web} twin, asks the access matrix for the
 * payables action it performs, and nothing else: reads need read, recording a payable needs create and
 * recording a payment against one needs edit. A new handler fails here until it is given its row.
 */
class PayableGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them. */
    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            Map.entry("PayableController#createPayable", "create"),
            Map.entry("PayableController#recordPayment", "edit"),
            Map.entry("PayableController#getPayableById", "read"),
            Map.entry("PayableController#getAllPayables", "read"),
            Map.entry("PayableController#getAllPayablesPaginated", "read"),
            Map.entry("PayableController#getPayablesByVendor", "read"),
            Map.entry("PayableController#getOutstandingPayables", "read"),
            Map.entry("PayableControllerWeb#createPayable", "create"),
            Map.entry("PayableControllerWeb#recordPayment", "edit"),
            Map.entry("PayableControllerWeb#getPayableById", "read"),
            Map.entry("PayableControllerWeb#getAllPayables", "read"),
            Map.entry("PayableControllerWeb#getAllPayablesPaginated", "read"),
            Map.entry("PayableControllerWeb#getPayablesByVendor", "read"),
            Map.entry("PayableControllerWeb#getOutstandingPayables", "read"));

    @Test
    void everyHandlerNeedsThePayablesActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(PayableGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(ACTION_BY_HANDLER.keySet());
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo("@access.can('payables', '" + action + "')"));
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(PayableController.class, PayableControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
