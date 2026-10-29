package org.tornotron.echno_backend.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Every asset handler asks the access matrix, never a role name or bare membership, and each kind
 * of request needs its own action: reads view, creates (an asset or a movement) create, changes
 * edit, deletes delete.
 */
class AssetGuardsTest {

    private static final Map<Class<? extends Annotation>, String> GUARD_BY_MAPPING = Map.of(
            GetMapping.class, AssetControllerWeb.VIEW_GUARD,
            PostMapping.class, AssetControllerWeb.CREATE_GUARD,
            PutMapping.class, AssetControllerWeb.EDIT_GUARD,
            DeleteMapping.class, AssetControllerWeb.DELETE_GUARD);

    @Test
    void everyHandlerNeedsTheActionMatchingItsMethod() {
        assertThat(handlers()).hasSize(11).allSatisfy(handler -> {
            PreAuthorize guard = handler.getAnnotation(PreAuthorize.class);
            assertThat(guard).as("%s has a guard", handler.getName()).isNotNull();
            String expected = GUARD_BY_MAPPING.entrySet().stream()
                    .filter(entry -> handler.isAnnotationPresent(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst().orElseThrow();
            assertThat(guard.value()).as("%s", handler.getName()).isEqualTo(expected);
        });
    }

    private static List<Method> handlers() {
        return Arrays.stream(AssetControllerWeb.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
