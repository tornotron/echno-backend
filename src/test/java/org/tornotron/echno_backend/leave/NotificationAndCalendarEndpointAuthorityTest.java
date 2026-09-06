package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The notification inbox and the leave calendar may not be guarded by an authority this realm has
 * no way to issue, and may not take the person they answer about from the caller.
 *
 * <p>Eleven endpoints, all five on {@link NotificationController} and all six on
 * {@link LeaveCalendarController}, asked for {@code hasAuthority('leave:read')} or
 * {@code hasAuthority('leave:admin')}. A bare {@code resource:scope} authority is minted in exactly
 * one place, {@code JwtAuthConverter.extractPermissions}, from the {@code authorization} claim of
 * an RPT, so each needed a Keycloak Authorization Services resource named {@code leave} carrying
 * the matching scope. The only automated provisioner is
 * {@code KeycloakInitializer.ensureAuthorizationSetup}, and it registers a {@code Default Resource}
 * on which {@code setScopes} is never called plus a scopeless {@code Default Permission}; a
 * permission with no scopes yields no {@code resource:scope} authority at all. The multi-tenancy
 * audit of 2026-08-18 confirmed the same end to end against the live staging Keycloak database for
 * the identical {@code organization:admin} string, finding zero scopes realm-wide. #641 confirmed
 * it for {@code billing:admin} and #685 for {@code leave:approve}.
 *
 * <p>This is the ratchet that stops the phantom being reintroduced by copying a neighbouring
 * annotation. It reads the annotations off the production classes rather than the source text, and
 * the second test pins the endpoint count so the first cannot pass by a guard having been deleted
 * rather than corrected.
 *
 * <p>The remaining tests pin the other half. The {@code /web} twins of both controllers were never
 * phantom-guarded and so were never dead: they asked for a role and then answered about whichever
 * employee, manager or organization the caller named, which is the shape closed in #589, #599,
 * #607, #631, #635 and #683. Repairing only the authority would have handed whoever got through
 * every colleague's inbox rather than nobody's.
 */
class NotificationAndCalendarEndpointAuthorityTest {

    /** Both twins of both controllers. Checking one twin is how the last one of these was missed. */
    private static final List<Class<?>> CONTROLLERS = List.of(
            NotificationController.class,
            NotificationControllerWeb.class,
            LeaveCalendarController.class,
            LeaveCalendarControllerWeb.class);

    /**
     * A bare {@code resource:scope} grant inside a hasAuthority call, which is the form that needs
     * an authorization scope the realm does not define. Org-scoped authorities built at runtime,
     * such as {@code ORG_MEMBER_7}, are a different mechanism and do not match.
     */
    private static final Pattern UNGRANTABLE_SCOPE_AUTHORITY =
            Pattern.compile("hasAuthority\\(\\s*'[a-z-]+:[a-z-]+'\\s*\\)");

    /** Parameter names that name a person, or the tenant, rather than the thing being read. */
    private static final List<String> CALLER_SUPPLIED_IDENTITY =
            List.of("employeeId", "managerId", "approverId", "organizationId", "recipientId");

    private record Endpoint(String name, String guard) {}

    private static List<Endpoint> endpoints() {
        List<Endpoint> found = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || !isRequestMapped(method)) {
                    continue;
                }
                PreAuthorize guard = method.getAnnotation(PreAuthorize.class);
                found.add(new Endpoint(
                        controller.getSimpleName() + "." + method.getName(),
                        guard == null ? null : guard.value()));
            }
        }
        return found;
    }

    private static boolean isRequestMapped(Method method) {
        return Arrays.stream(method.getAnnotations())
                .anyMatch(annotation ->
                        annotation.annotationType().isAnnotationPresent(RequestMapping.class)
                                || annotation.annotationType().equals(RequestMapping.class));
    }

    @Test
    void noNotificationOrCalendarEndpointIsGuardedByAnAuthorityTheRealmCannotIssue() {
        List<String> offenders = endpoints().stream()
                .filter(endpoint -> endpoint.guard() != null)
                .filter(endpoint -> UNGRANTABLE_SCOPE_AUTHORITY.matcher(endpoint.guard()).find())
                .map(endpoint -> endpoint.name() + " asks for " + endpoint.guard())
                .toList();

        assertThat(offenders)
                .as("notification and leave calendar endpoints guarded by a resource:scope authority "
                        + "that nothing in the realm grants, so they refuse every caller forever")
                .isEmpty();
    }

    @Test
    void everyNotificationAndCalendarEndpointIsStillGuardedBySomething() {
        // Without this, the rule above passes for the worst possible reason: an endpoint whose
        // guard was deleted rather than corrected is not an offender, and neither is a deleted
        // endpoint. Pinning the count is what makes the absence of offenders mean something.
        List<Endpoint> endpoints = endpoints();

        assertThat(endpoints.stream().filter(endpoint -> endpoint.guard() == null).toList())
                .as("every request-mapped method on these four controllers carries a @PreAuthorize")
                .isEmpty();

        assertThat(endpoints)
                .as("the notification inbox and the leave calendar still expose their endpoints on "
                        + "both twins: five plus five plus six plus six")
                .hasSize(22);
    }

    @Test
    void noInboxOrCalendarEndpointTakesThePersonItAnswersAboutFromTheCaller() {
        // The exception is the one endpoint whose guard reads the id it is given: an employee's own
        // calendar is gated on isSelfOrHasAnyOrgRole(#employeeId, ...), which is the whole point of
        // that parameter. Everywhere else the caller named somebody and the guard checked only a
        // role or an authority, so nothing tied the two together.
        List<String> offenders = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || !isRequestMapped(method)) {
                    continue;
                }
                PreAuthorize guard = method.getAnnotation(PreAuthorize.class);
                String expression = guard == null ? "" : guard.value();
                for (Parameter parameter : method.getParameters()) {
                    if (!isBoundToTheRequest(parameter)) {
                        continue;
                    }
                    String name = parameter.getName();
                    if (CALLER_SUPPLIED_IDENTITY.contains(name) && !expression.contains("#" + name)) {
                        offenders.add(controller.getSimpleName() + "." + method.getName()
                                + " takes " + name + " under a guard that does not read it: " + expression);
                    }
                }
            }
        }

        assertThat(offenders)
                .as("handlers that answer about whoever the caller named, under a guard that only "
                        + "establishes that the caller holds a role")
                .isEmpty();
    }

    @Test
    void anInboxIsTheCallersOwnOnBothTwins() {
        // Each of these declared @RequestParam Long employeeId. A notification is addressed to one
        // person, so there is nothing for a client to choose.
        for (Class<?> controller : List.of(NotificationController.class, NotificationControllerWeb.class)) {
            assertThat(requestParameterNames(controller, "getNotifications")).isEmpty();
            assertThat(requestParameterNames(controller, "getUnreadNotifications")).isEmpty();
            assertThat(requestParameterNames(controller, "getUnreadCount")).isEmpty();
            assertThat(requestParameterNames(controller, "markAllAsRead")).isEmpty();
        }

        // Marking read still names the notification, on a path segment on the phone and a query
        // parameter in the console. That id names the thing, not the person, and it is the reason
        // a repaired guard could never have closed this one: there is no recipient here for a
        // self-check to read, so ownership is settled against the recipient stored on the row.
        // See NotificationInboxOwnershipTest.
        assertThat(requestParameterNames(NotificationController.class, "markAsRead"))
                .containsExactly("notificationId");
        assertThat(requestParameterNames(NotificationControllerWeb.class, "markAsRead"))
                .containsExactly("notificationId");
    }

    @Test
    void aTeamCalendarIsTheCallersOwnAndTheTenantComesFromTheSession() {
        for (Class<?> controller : List.of(LeaveCalendarController.class, LeaveCalendarControllerWeb.class)) {
            assertThat(requestParameterNames(controller, "getTeamCalendar"))
                    .as("the manager whose team it is comes from the session")
                    .containsExactly("startDate", "endDate");
            assertThat(requestParameterNames(controller, "getOrganizationCalendar"))
                    .containsExactly("startDate", "endDate");
            assertThat(requestParameterNames(controller, "getDepartmentCalendar"))
                    .containsExactly("department", "startDate", "endDate");
            assertThat(requestParameterNames(controller, "getCalendarGroupedByDate"))
                    .containsExactly("startDate", "endDate");
            assertThat(requestParameterNames(controller, "getEmployeesOnLeaveCount"))
                    .containsExactly("date");
        }
    }

    /**
     * The names of the parameters this handler binds from the request, in declaration order.
     *
     * <p>Reads {@code @RequestParam} and {@code @PathVariable}, and falls back to the parameter's
     * own name for the implicitly bound ones such as {@code Pageable}, which is why a value is
     * only ever asserted against an explicit list.
     */
    private static List<String> requestParameterNames(Class<?> controller, String methodName) {
        return Arrays.stream(endpointMethod(controller, methodName).getParameters())
                .filter(NotificationAndCalendarEndpointAuthorityTest::isBoundToTheRequest)
                .map(Parameter::getName)
                .toList();
    }

    private static boolean isBoundToTheRequest(Parameter parameter) {
        return parameter.isAnnotationPresent(RequestParam.class)
                || parameter.isAnnotationPresent(PathVariable.class);
    }

    private static Method endpointMethod(Class<?> controller, String methodName) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        controller.getSimpleName() + " has no method named " + methodName));
    }
}
