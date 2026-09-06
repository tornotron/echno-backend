package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The leave approval workflow may not be guarded by an authority this realm has no way to issue.
 *
 * <p>Five of the six endpoints on {@link LeaveApprovalController} asked for
 * {@code hasAuthority('leave:approve')}, {@code 'leave:read'} or {@code 'leave:admin'}.
 * {@code JwtAuthConverter} mints a bare {@code resource:scope} authority in exactly one place,
 * {@code extractPermissions}, which reads the {@code authorization} claim of an RPT, so each of
 * those strings needed a Keycloak Authorization Services resource named {@code leave} carrying the
 * matching scope. The multi-tenancy audit of 2026-08-18 traced the identical case of
 * {@code organization:admin} against the live staging realm and found zero authorization scopes
 * realm-wide, one scopeless Default Resource and one scopeless Default Permission; a scopeless
 * permission yields no {@code resource:scope} authority at all. #641 confirmed the same for
 * {@code billing:admin} and fixed the billing surface.
 *
 * <p>So rejecting a leave request, delegating one, reading its approval trail and asking whether
 * you may act on it all refused every caller, forever, while looking guarded. Approvals are a
 * required workflow on mobile, so they were repaired rather than deleted.
 *
 * <p>This is the ratchet that stops the phantom being reintroduced by copying a neighbouring
 * annotation. It reads the annotations off the production classes rather than the source text, and
 * the second test pins the endpoint count so that the first cannot pass by an endpoint's guard
 * having been deleted rather than corrected, or by the controller having been emptied.
 *
 * <p>Scoped to the leave approval surface deliberately. {@code NotificationController} and
 * {@code LeaveCalendarController} in this same package carried the same phantom and were repaired
 * separately in #684; {@code NotificationAndCalendarEndpointAuthorityTest} is their ratchet.
 */
class LeaveApprovalEndpointAuthorityTest {

    /** The controllers that make up the leave approval workflow, on both twins. */
    private static final List<Class<?>> APPROVAL_CONTROLLERS = List.of(
            LeaveApprovalController.class,
            LeaveApprovalControllerWeb.class,
            LeaveRequestController.class,
            LeaveRequestControllerWeb.class);

    /**
     * A bare {@code resource:scope} grant inside a hasAuthority call, which is the form that needs
     * an authorization scope the realm does not define. Org-scoped authorities built at runtime,
     * such as {@code ORG_MEMBER_7}, are a different mechanism and do not match.
     */
    private static final Pattern UNGRANTABLE_SCOPE_AUTHORITY =
            Pattern.compile("hasAuthority\\(\\s*'[a-z-]+:[a-z-]+'\\s*\\)");

    private record Endpoint(String name, String guard) {}

    private static List<Endpoint> endpoints() {
        List<Endpoint> found = new ArrayList<>();
        for (Class<?> controller : APPROVAL_CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                if (!isRequestMapped(method)) {
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
        return java.util.Arrays.stream(method.getAnnotations())
                .anyMatch(annotation ->
                        annotation.annotationType().isAnnotationPresent(RequestMapping.class)
                                || annotation.annotationType().equals(RequestMapping.class));
    }

    @Test
    void noLeaveApprovalEndpointIsGuardedByAnAuthorityTheRealmCannotIssue() {
        List<String> offenders = endpoints().stream()
                .filter(endpoint -> endpoint.guard() != null)
                .filter(endpoint -> UNGRANTABLE_SCOPE_AUTHORITY.matcher(endpoint.guard()).find())
                .map(endpoint -> endpoint.name() + " asks for " + endpoint.guard())
                .toList();

        assertThat(offenders)
                .as("leave approval endpoints guarded by a resource:scope authority that nothing in "
                        + "the realm grants, so they refuse every caller forever")
                .isEmpty();
    }

    @Test
    void everyLeaveApprovalEndpointIsStillGuardedBySomething() {
        // Without this, the rule above passes for the worst possible reason: an endpoint whose
        // guard was deleted rather than corrected is not an offender, and neither is a deleted
        // endpoint. Pinning the count is what makes the absence of offenders mean something.
        List<Endpoint> endpoints = endpoints();

        assertThat(endpoints.stream().filter(endpoint -> endpoint.guard() == null).toList())
                .as("every request-mapped method on the leave approval surface carries a @PreAuthorize")
                .isEmpty();

        assertThat(endpoints)
                .as("the four leave approval and leave request controllers still expose their endpoints")
                .hasSizeGreaterThanOrEqualTo(38);
    }

    @Test
    void actingOnAnApprovalIsGatedOnMembershipBecauseTheRecordDecidesWhoMayAct() {
        // The role gate this replaces could not express the rule. An approval chain is built by
        // walking the employee's management line, so an approver is a manager and holds neither
        // system-admin nor hr-admin: the guard refused the very people who hold the decision,
        // while an administrator who was not in the chain got past it and was refused by the
        // service. Membership is what the annotation can evaluate; the service settles the rest.
        assertThat(guardOf(LeaveApprovalController.class, "approve"))
                .isEqualTo("@orgSecurity.isMemberOfCurrentTenant()");
        assertThat(guardOf(LeaveApprovalController.class, "reject"))
                .isEqualTo("@orgSecurity.isMemberOfCurrentTenant()");
        assertThat(guardOf(LeaveApprovalController.class, "delegate"))
                .isEqualTo("@orgSecurity.isMemberOfCurrentTenant()");
        assertThat(guardOf(LeaveApprovalControllerWeb.class, "approve"))
                .isEqualTo("@orgSecurity.isMemberOfCurrentTenant()");
        assertThat(guardOf(LeaveApprovalControllerWeb.class, "reject"))
                .isEqualTo("@orgSecurity.isMemberOfCurrentTenant()");
        assertThat(guardOf(LeaveApprovalControllerWeb.class, "delegate"))
                .isEqualTo("@orgSecurity.isMemberOfCurrentTenant()");
    }

    @Test
    void anApprovalQueueTakesNoApproverParameter() {
        // A queue is the caller's own by definition. Taking the approver as a parameter under a
        // guard that only checked a role let an administrator read a colleague's queue and left
        // the managers an approval chain is built from unable to read their own. Each of these
        // declared one parameter, @RequestParam Long approverId, and now declares none.
        assertThat(parameterCount(LeaveRequestController.class, "getPendingApprovals")).isZero();
        assertThat(parameterCount(LeaveRequestController.class, "getPendingApprovalCount")).isZero();
        assertThat(parameterCount(LeaveRequestControllerWeb.class, "getPendingApprovals")).isZero();
        assertThat(parameterCount(LeaveRequestControllerWeb.class, "getPendingApprovalCount")).isZero();
        assertThat(parameterCount(LeaveRequestControllerWeb.class, "getRequestsByApprover")).isZero();
    }

    @Test
    void theCanApproveCheckAsksAboutTheCallerRatherThanAnEmployeeTheySend() {
        // The request is the only thing left to name. Asking "can employee 9 approve request 12"
        // is a question no client has ever needed answered, and it let one employee probe
        // another's place in a chain. The employeeId parameter is gone from both twins.
        assertThat(parameterCount(LeaveApprovalController.class, "canApprove")).isEqualTo(1);
        assertThat(parameterCount(LeaveApprovalControllerWeb.class, "canApprove")).isEqualTo(1);
        assertThat(requestParameterCount(LeaveApprovalController.class, "canApprove")).isZero();
        assertThat(requestParameterCount(LeaveApprovalControllerWeb.class, "canApprove")).isEqualTo(1);
    }

    private static String guardOf(Class<?> controller, String methodName) {
        return endpointMethod(controller, methodName).getAnnotation(PreAuthorize.class).value();
    }

    private static int parameterCount(Class<?> controller, String methodName) {
        return endpointMethod(controller, methodName).getParameterCount();
    }

    private static long requestParameterCount(Class<?> controller, String methodName) {
        return java.util.Arrays.stream(endpointMethod(controller, methodName).getParameters())
                .filter(parameter -> parameter.isAnnotationPresent(
                        org.springframework.web.bind.annotation.RequestParam.class))
                .count();
    }

    private static Method endpointMethod(Class<?> controller, String methodName) {
        return java.util.Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        controller.getSimpleName() + " has no method named " + methodName));
    }
}
