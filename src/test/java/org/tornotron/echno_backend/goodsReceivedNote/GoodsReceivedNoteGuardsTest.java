package org.tornotron.echno_backend.goodsReceivedNote;

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
 * Every goods receipt handler, on the tenant API and its {@code /web} twin, asks the access matrix for
 * the goods receipts action it performs, and nothing else: reads need read, recording a delivery needs
 * create and correcting a receipt needs edit. A new handler fails here until it is given its row.
 */
class GoodsReceivedNoteGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them. */
    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            Map.entry("GoodsReceivedNoteController#createGrn", "create"),
            Map.entry("GoodsReceivedNoteController#getGrnById", "read"),
            Map.entry("GoodsReceivedNoteController#getAllGrns", "read"),
            Map.entry("GoodsReceivedNoteController#getAllGrnsPaginated", "read"),
            Map.entry("GoodsReceivedNoteController#getGrnsByVendor", "read"),
            Map.entry("GoodsReceivedNoteController#updateGrn", "edit"),
            Map.entry("GoodsReceivedNoteController#getGrnsByDateRange", "read"),
            Map.entry("GoodsReceivedNoteControllerWeb#createGrn", "create"),
            Map.entry("GoodsReceivedNoteControllerWeb#getGrnById", "read"),
            Map.entry("GoodsReceivedNoteControllerWeb#getAllGrns", "read"),
            Map.entry("GoodsReceivedNoteControllerWeb#getAllGrnsPaginated", "read"),
            Map.entry("GoodsReceivedNoteControllerWeb#getGrnsByVendor", "read"),
            Map.entry("GoodsReceivedNoteControllerWeb#updateGrn", "edit"),
            Map.entry("GoodsReceivedNoteControllerWeb#getGrnsByDateRange", "read"));

    @Test
    void everyHandlerNeedsTheGoodsReceiptsActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(GoodsReceivedNoteGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(ACTION_BY_HANDLER.keySet());
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo("@access.can('goods-receipts', '" + action + "')"));
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(GoodsReceivedNoteController.class, GoodsReceivedNoteControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
