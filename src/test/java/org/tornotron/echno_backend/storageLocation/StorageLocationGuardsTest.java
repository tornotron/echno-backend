package org.tornotron.echno_backend.storageLocation;

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
 * Every storage location handler, on the tenant API and its {@code /web} twin, asks the access matrix
 * for the storage locations action it performs, and nothing else: reads need read, and defining,
 * changing and removing a location need create, edit and delete. A new handler fails here until it is
 * given its row.
 */
class StorageLocationGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them. */
    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            Map.entry("StorageLocationController#createStorageLocation", "create"),
            Map.entry("StorageLocationController#getStorageLocationById", "read"),
            Map.entry("StorageLocationController#getAllStorageLocations", "read"),
            Map.entry("StorageLocationController#getAllStorageLocationsPaginated", "read"),
            Map.entry("StorageLocationController#getStorageLocationsByProject", "read"),
            Map.entry("StorageLocationController#getStorageLocationsByType", "read"),
            Map.entry("StorageLocationControllerWeb#createStorageLocation", "create"),
            Map.entry("StorageLocationControllerWeb#getStorageLocationById", "read"),
            Map.entry("StorageLocationControllerWeb#getAllStorageLocations", "read"),
            Map.entry("StorageLocationControllerWeb#getAllStorageLocationsPaginated", "read"),
            Map.entry("StorageLocationControllerWeb#getStorageLocationsByProject", "read"),
            Map.entry("StorageLocationControllerWeb#getStorageLocationsByType", "read"),
            Map.entry("StorageLocationControllerWeb#updateStorageLocation", "edit"),
            Map.entry("StorageLocationControllerWeb#deleteStorageLocation", "delete"));

    @Test
    void everyHandlerNeedsTheStorageLocationsActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(StorageLocationGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(ACTION_BY_HANDLER.keySet());
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo("@access.can('storage-locations', '" + action + "')"));
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(StorageLocationController.class, StorageLocationControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
