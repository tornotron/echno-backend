package org.tornotron.echno_backend.employee;

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
 * Every employee handler, on the tenant API and its {@code /web} twin, asks the access matrix for the
 * action it performs, and says whose record it is about where the old guard did: the employee the
 * call names ({@code @teamAccess.allowsFor}) or everyone's ({@code @teamAccess.acrossOrganization}).
 * Who reports to whom is the named read {@code read-reporting-lines}. A new handler fails here until
 * it is given its row.
 */
class TeamGuardsTest {

    static final String DENIED = "denyAll()";
    static final String IN_THIS_ORGANIZATION = "@orgSecurity.isCurrentTenant(#%s) and ";

    /** Keyed by controller and handler, as {@link #key} names them; valued by the whole guard. */
    static final Map<String, String> GUARD_BY_HANDLER = Map.ofEntries(
            Map.entry("EmployeeController#createEmployee", DENIED),
            Map.entry("EmployeeController#readAllEmployees", acrossOrganization("read")),
            Map.entry("EmployeeController#readAnEmployee", forEmployee("read", "id")),
            Map.entry("EmployeeController#readEmployeesByOrganizationId",
                    IN_THIS_ORGANIZATION.formatted("id") + acrossOrganization("read")),
            Map.entry("EmployeeController#partialUpdateAnEmployee", forEmployee("edit", "id")),
            Map.entry("EmployeeController#batchUpdateEmployees", acrossOrganization("edit")),
            Map.entry("EmployeeController#deleteEmployee", acrossOrganization("delete")),
            Map.entry("EmployeeControllerWeb#lookupEmployees", can("read")),
            Map.entry("EmployeeControllerWeb#readAllEmployees", acrossOrganization("read")),
            Map.entry("EmployeeControllerWeb#readAllEmployeesPaginated", acrossOrganization("read")),
            Map.entry("EmployeeControllerWeb#readAnEmployee", forEmployee("read", "id")),
            Map.entry("EmployeeControllerWeb#partialUpdateAnEmployee", forEmployee("edit", "id")),
            Map.entry("EmployeeControllerWeb#deleteEmployee", acrossOrganization("delete")),
            Map.entry("EmployeeControllerWeb#assignManager", acrossOrganization("edit")),
            Map.entry("EmployeeControllerWeb#removeManager", acrossOrganization("edit")),
            Map.entry("EmployeeControllerWeb#getDirectSubordinates", forEmployee("read-reporting-lines", "managerId")),
            Map.entry("EmployeeControllerWeb#getAllTheManagers", acrossOrganization("read")),
            Map.entry("EmployeeControllerWeb#getAllManagersForAnOrganization",
                    IN_THIS_ORGANIZATION.formatted("organizationId") + acrossOrganization("read")),
            Map.entry("EmployeeControllerWeb#assignOrgRole", acrossOrganization("edit")),
            Map.entry("EmployeeControllerWeb#removeOrgRole", acrossOrganization("edit")),
            Map.entry("EmployeeControllerWeb#getOrgRoles", can("read")));

    @Test
    void everyHandlerNeedsTheActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(TeamGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards).containsExactlyInAnyOrderEntriesOf(GUARD_BY_HANDLER);
    }

    static String can(String action) {
        return "@access.can('team', '" + action + "')";
    }

    static String forEmployee(String action, String parameter) {
        return can(action) + " and @teamAccess.allowsFor('" + action + "', #" + parameter + ")";
    }

    static String acrossOrganization(String action) {
        return can(action) + " and @teamAccess.acrossOrganization('" + action + "')";
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(EmployeeController.class, EmployeeControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
