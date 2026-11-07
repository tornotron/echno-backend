package org.tornotron.echno_backend.organization;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two annotations that decided whether a new account could get started at all.
 *
 * <p>Creating an organization is the first thing a self-registered account does and the only way it
 * acquires a role, so anything the endpoint demands up front, an organization authority it cannot
 * hold yet or a subscription it cannot have bought, closes the door on the account permanently.
 * Both guards were doing exactly that. This reads the annotations back off the handlers so a guard
 * put back by habit fails here rather than on someone's first day using the product.
 *
 * <p>The one thing added since is {@code OrganizationCreationPolicy}, which keeps self-service on
 * wherever it was (every Keycloak stack, unless it says otherwise) and asks for a platform
 * administrator only where a stack turns it off. It asks nothing a new account could not hold.
 *
 * <p>Creating an organization is still not unguarded. The caller must be authenticated, the new
 * organization is a tenant of its own that touches no existing one, and the billing rule now lives
 * in {@code OrganizationService}, which exempts a user's first organization and charges the rest.
 */
class OrganizationCreateBootstrapWiringTest {

    private Method createOrganization(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> method.getName().equals("createOrganization"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No createOrganization handler on " + controller.getName()));
    }

    @Test
    void webCreateAsksOnlyForAnAuthenticatedCallerAndTheCreationPolicy() {
        PreAuthorize guard = createOrganization(OrganizationWebController.class).getAnnotation(PreAuthorize.class);

        assertThat(guard).isNotNull();
        assertThat(guard.value()).isEqualTo("isAuthenticated() and @organizationCreation.allows(authentication)");
    }

    @Test
    void mobileCreateAsksOnlyForAnAuthenticatedCallerAndTheCreationPolicy() {
        // This one required 'organization:create', an authority a self-registered account has no
        // way to obtain, because the endpoint that grants roles is the one being guarded.
        PreAuthorize guard = createOrganization(OrganizationController.class).getAnnotation(PreAuthorize.class);

        assertThat(guard).isNotNull();
        assertThat(guard.value()).isEqualTo("isAuthenticated() and @organizationCreation.allows(authentication)");
    }

    @Test
    void neitherCreateEndpointGatesOnASubscriptionUpFront() {
        // The subscription check has to know whether this is the caller's first organization, and
        // only the service knows that. Left on the handler it refused every new account with 402
        // "No active subscription" before the first organization could ever exist.
        assertThat(createOrganization(OrganizationWebController.class).getAnnotation(RequireSubscription.class))
                .isNull();
        assertThat(createOrganization(OrganizationController.class).getAnnotation(RequireSubscription.class))
                .isNull();
    }
}
