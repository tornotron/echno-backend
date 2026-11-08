package org.tornotron.echno_backend.indent;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.tornotron.echno_backend.indentItem.IndentItemControllerWeb;

/**
 * Every web indent handler asks the access matrix for the action it performs, and nothing else: reads
 * need read, raising an indent or adding a line needs create, a change needs edit and a removal needs
 * delete. A new handler fails here until it is given its row.
 *
 * <p>Handlers are keyed by controller, method and mapping, since {@code getAllIndents} is two endpoints.
 */
class IndentGuardsTest {

    /** Every handler, by the action it asks for. */
    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            entry("IndentControllerWeb.getAllIndents GET /all", "read"),
            entry("IndentControllerWeb.getAllIndentSummaries GET /summary", "read"),
            entry("IndentControllerWeb.getAllIndents GET", "read"),
            entry("IndentControllerWeb.getAnIndent GET /{id}", "read"),
            entry("IndentControllerWeb.getItems GET /{indentId}/items", "read"),
            entry("IndentControllerWeb.deleteIndent DELETE /{id}", "delete"),
            entry("IndentControllerWeb.deleteItem DELETE /{indentId}/items/{itemId}", "delete"),
            entry("IndentItemControllerWeb.deleteIndentItem DELETE /{id}", "delete"),
            entry("IndentControllerWeb.createIndent POST", "create"),
            entry("IndentControllerWeb.UpdateAnIndent PATCH /{id}", "edit"),
            entry("IndentControllerWeb.addItem POST /{indentId}/items", "create"),
            entry("IndentControllerWeb.updateItem PATCH /{indentId}/items/{itemId}", "edit"),
            entry("IndentItemControllerWeb.createIndentItem POST", "create"),
            entry("IndentItemControllerWeb.updateIndentItem PUT /{id}", "edit"),
            entry("IndentItemControllerWeb.markAsConverted PUT /{id}/mark-converted", "edit"),
            entry("IndentItemControllerWeb.getIndentItemById GET /{id}", "read"),
            entry("IndentItemControllerWeb.getAllIndentItems GET", "read"),
            entry("IndentItemControllerWeb.getIndentItemsPaginated GET /paginated", "read"),
            entry("IndentItemControllerWeb.getIndentItemsByIndentId GET /indent/{indentId}", "read"),
            entry("IndentItemControllerWeb.getIndentItemsByMaterialId GET /material/{materialId}", "read"),
            entry("IndentItemControllerWeb.getIndentItemsByConversionStatus GET /conversion-status", "read"));

    @Test
    void everyHandlerCarriesItsGuard() {
        Map<String, String> guards = handlers().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                handler -> handler.getValue().getAnnotation(PreAuthorize.class).value()));

        Map<String, String> expected = new LinkedHashMap<>();
        ACTION_BY_HANDLER.forEach((handler, action) ->
                expected.put(handler, "@access.can('indents', '" + action + "')"));

        assertThat(guards).containsExactlyInAnyOrderEntriesOf(expected);
    }

    /** Every request handler on the two web controllers, by signature. */
    static Map<String, Method> handlers() {
        Map<String, Method> handlers = new LinkedHashMap<>();
        for (Class<?> controller : List.of(IndentControllerWeb.class, IndentItemControllerWeb.class)) {
            Arrays.stream(controller.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .filter(method -> Arrays.stream(method.getAnnotations())
                            .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                    .forEach(method -> {
                        String signature = signature(controller, method);
                        assertThat(handlers.put(signature, method)).as("two handlers on " + signature).isNull();
                    });
        }
        return handlers;
    }

    private static String signature(Class<?> controller, Method method) {
        String verb = null;
        String[] paths = null;
        for (Annotation annotation : method.getAnnotations()) {
            if (annotation instanceof GetMapping m) { verb = "GET"; paths = m.value(); }
            if (annotation instanceof PostMapping m) { verb = "POST"; paths = m.value(); }
            if (annotation instanceof PutMapping m) { verb = "PUT"; paths = m.value(); }
            if (annotation instanceof PatchMapping m) { verb = "PATCH"; paths = m.value(); }
            if (annotation instanceof DeleteMapping m) { verb = "DELETE"; paths = m.value(); }
        }
        String path = paths == null || paths.length == 0 ? "" : " " + paths[0];
        return controller.getSimpleName() + "." + method.getName() + " " + verb + path;
    }
}
