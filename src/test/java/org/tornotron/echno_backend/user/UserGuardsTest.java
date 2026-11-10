package org.tornotron.echno_backend.user;

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
 * Every user account handler, on the tenant API and its {@code /web} twin, asks the access matrix for
 * the action it performs. Listing the organization's accounts is the named {@code read-user-accounts},
 * and changing, deleting or batch-changing another person's account, or listing their organizations, is
 * the named {@code manage-user-accounts}, both across the organization. A caller acting on their own
 * account still passes by who they are. The handlers about the signed-in caller alone stay open to any
 * authenticated caller. A new handler fails here until it is given its row.
 */
class UserGuardsTest {

    private static final String OPEN = "isAuthenticated()";

    /** Keyed by controller and handler, as {@link #key} names them; valued by the whole guard. */
    static final Map<String, String> GUARD_BY_HANDLER = Map.ofEntries(
            Map.entry("UserController#readAllUsers", acrossOrganization("read-user-accounts")),
            Map.entry("UserController#readAllOrganizationsForCurrentUser", selfOr("userId")),
            Map.entry("UserController#readAnUser", OPEN),
            Map.entry("UserController#partialUpdateAUser", selfOr("id")),
            Map.entry("UserController#batchUpdateUsers", acrossOrganization("manage-user-accounts")),
            Map.entry("UserController#deleteAnUser", acrossOrganization("manage-user-accounts")),
            Map.entry("UserControllerWeb#readAllUsers", acrossOrganization("read-user-accounts")),
            Map.entry("UserControllerWeb#readAllOrganizationsForCurrentUser", selfOr("userId")),
            Map.entry("UserControllerWeb#getCurrentUser", OPEN),
            Map.entry("UserControllerWeb#getEmployeesForCurrentUser", OPEN),
            Map.entry("UserControllerWeb#partialUpdateAUser", selfOr("id")),
            Map.entry("UserControllerWeb#batchUpdateUsers", acrossOrganization("manage-user-accounts")),
            Map.entry("UserControllerWeb#deleteAnUser", acrossOrganization("manage-user-accounts")));

    @Test
    void everyHandlerNeedsTheActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(UserGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards).containsExactlyInAnyOrderEntriesOf(GUARD_BY_HANDLER);
    }

    static String acrossOrganization(String action) {
        return "@access.can('team', '" + action + "') and @teamAccess.acrossOrganization('" + action + "')";
    }

    static String selfOr(String parameter) {
        return "@orgSecurity.isSelfUser(#" + parameter + ") or (" + acrossOrganization("manage-user-accounts") + ")";
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(UserController.class, UserControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
