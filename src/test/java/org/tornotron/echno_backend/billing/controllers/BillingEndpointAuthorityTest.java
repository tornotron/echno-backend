package org.tornotron.echno_backend.billing.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The billing surface may not be guarded by an authority this realm has no way to issue.
 *
 * <p>Every endpoint on all three billing controllers used to ask for
 * {@code hasAuthority('billing:admin')}. That string occurred 45 times in the source and every
 * one of them was the annotation itself: nothing granted it, and nothing could.
 * {@code JwtAuthConverter} mints a bare {@code resource:scope} authority in exactly one place,
 * {@code extractPermissions}, which reads the {@code authorization} claim of an RPT, so the
 * string required a Keycloak Authorization Services resource named {@code billing} carrying an
 * {@code admin} scope. The multi-tenancy audit of 2026-08-18 checked the live realm for the
 * identical case of {@code organization:admin} and found zero authorization scopes realm-wide,
 * one scopeless {@code Default Resource} and one scopeless {@code Default Permission}. A
 * scopeless permission yields no {@code resource:scope} authority at all.
 *
 * <p>So the whole administrative billing surface was permanently refused, and it looked guarded
 * rather than dead. This test is what stops that from being reintroduced by copying a neighbouring
 * annotation. See #641.
 *
 * <p>The rule is deliberately scoped to the billing package. The same phantom shape exists
 * elsewhere in the codebase, notably {@code organization:admin}, and is worth the same treatment,
 * but widening this test is a change to endpoints outside the package it was written for.
 */
class BillingEndpointAuthorityTest {

    /** The three controllers that make up the billing HTTP surface. */
    private static final List<Class<?>> BILLING_CONTROLLERS =
            List.of(SubscriptionController.class, PlanController.class, FeatureController.class);

    /**
     * A bare {@code resource:scope} grant inside a hasAuthority call, which is the form that
     * needs an authorization scope the realm does not define. Org-scoped authorities built at
     * runtime, such as {@code ORG_MEMBER_7}, do not match and are not the subject here.
     */
    private static final Pattern UNGRANTABLE_SCOPE_AUTHORITY =
            Pattern.compile("hasAuthority\\(\\s*'([a-z]+:[a-z]+)'\\s*\\)");

    private record Guarded(String endpoint, String expression) {}

    private List<Guarded> guardedEndpoints() {
        List<Guarded> guarded = new ArrayList<>();
        for (Class<?> controller : BILLING_CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
                if (preAuthorize != null) {
                    guarded.add(new Guarded(
                            controller.getSimpleName() + "." + method.getName(),
                            preAuthorize.value()));
                }
            }
        }
        return guarded;
    }

    @Test
    void noBillingEndpointIsGuardedByAnAuthorityTheRealmCannotIssue() {
        List<String> offenders = guardedEndpoints().stream()
                .filter(g -> {
                    Matcher m = UNGRANTABLE_SCOPE_AUTHORITY.matcher(g.expression());
                    return m.find();
                })
                .map(g -> g.endpoint() + " asks for " + g.expression())
                .toList();

        assertThat(offenders)
                .as("billing endpoints guarded by a resource:scope authority that nothing in the "
                        + "realm grants, so they refuse every caller forever")
                .isEmpty();
    }

    @Test
    void everyBillingEndpointIsStillGuardedBySomething() {
        // Without this, the rule above passes for the worst possible reason: an endpoint whose
        // guard was deleted rather than corrected is not an offender, and neither is a deleted
        // controller. Pinning the count is what makes the absence of offenders mean something.
        List<Guarded> guarded = guardedEndpoints();

        long endpoints = BILLING_CONTROLLERS.stream()
                .flatMap(c -> Arrays.stream(c.getDeclaredMethods()))
                .filter(m -> m.getAnnotations().length > 0)
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .count();

        assertThat(guarded)
                .as("every public billing endpoint carries a @PreAuthorize")
                .hasSize((int) endpoints);
        assertThat(guarded)
                .as("the three billing controllers still expose their endpoints")
                .hasSizeGreaterThanOrEqualTo(24);
    }

    @Test
    void thePublicPlanCatalogueIsReadableByAnyAuthenticatedCaller() throws Exception {
        // GET /public is the read a pricing page makes, and it was gated on the platform-admin
        // authority along with everything else. A signed-in user who belongs to no organization
        // yet has to be able to see what plans exist before they can be sold one.
        Method publicPlans = PlanController.class.getDeclaredMethod("getPublicPlans");

        assertThat(publicPlans.getAnnotation(GetMapping.class).value()).containsExactly("/public");
        assertThat(publicPlans.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("isAuthenticated()");
    }
}
