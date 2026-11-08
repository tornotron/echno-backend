package org.tornotron.echno_backend.asset;

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
 * Every asset handler asks the access matrix for the action it performs, and nothing else: reads need
 * read, registering an asset or recording a movement needs create, a change needs edit and a removal
 * needs delete. A new handler fails here until it is given its row.
 */
class AssetGuardsTest {

    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            Map.entry("readAllAssets", "read"),
            Map.entry("readAllAssetsPaginated", "read"),
            Map.entry("readAnAsset", "read"),
            Map.entry("readMovements", "read"),
            Map.entry("readPlacementHistory", "read"),
            Map.entry("readDocuments", "read"),
            Map.entry("readExpiringDocuments", "read"),
            Map.entry("createAsset", "create"),
            Map.entry("recordMovement", "create"),
            Map.entry("updateAsset", "edit"),
            Map.entry("deleteAsset", "delete"));

    @Test
    void everyHandlerNeedsTheAssetsActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(Method::getName,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(ACTION_BY_HANDLER.keySet());
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo("@access.can('assets', '" + action + "')"));
    }

    static List<Method> handlers() {
        return Arrays.stream(AssetControllerWeb.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
