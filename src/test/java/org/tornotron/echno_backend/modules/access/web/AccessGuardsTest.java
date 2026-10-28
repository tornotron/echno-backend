package org.tornotron.echno_backend.modules.access.web;

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
 * Every access handler asks the access matrix, never a role name, and each kind of request needs
 * its own action: reads view, creates create, changes edit, deletes delete. Only
 * {@code /current-user-access} needs bare membership, since it is how a member learns what they
 * hold.
 */
class AccessGuardsTest {

    private static final Map<Class<? extends Annotation>, String> GUARD_BY_MAPPING = Map.of(
            GetMapping.class, AccessAdminControllerWeb.VIEW_GUARD,
            PostMapping.class, AccessAdminControllerWeb.CREATE_GUARD,
            PutMapping.class, AccessAdminControllerWeb.EDIT_GUARD,
            DeleteMapping.class, AccessAdminControllerWeb.DELETE_GUARD);

    @Test
    void everyAdministrationHandlerNeedsTheActionMatchingItsMethod() {
        assertThat(handlers(AccessAdminControllerWeb.class)).isNotEmpty().allSatisfy(handler -> {
            PreAuthorize guard = handler.getAnnotation(PreAuthorize.class);
            assertThat(guard).as("%s has a guard", handler.getName()).isNotNull();
            String expected = GUARD_BY_MAPPING.entrySet().stream()
                    .filter(entry -> handler.isAnnotationPresent(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst().orElseThrow();
            assertThat(guard.value()).as("%s", handler.getName()).isEqualTo(expected);
        });
    }

    @Test
    void theCurrentUsersAccessNeedsOnlyMembership() {
        assertThat(handlers(CurrentUserAccessControllerWeb.class)).singleElement()
                .satisfies(handler -> assertThat(handler.getAnnotation(PreAuthorize.class).value())
                        .isEqualTo("@access.isMember()"));
    }

    private static List<Method> handlers(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
