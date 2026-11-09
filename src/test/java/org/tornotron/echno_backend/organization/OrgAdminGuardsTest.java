package org.tornotron.echno_backend.organization;

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
import org.tornotron.echno_backend.billing.controllers.BillingHistoryController;
import org.tornotron.echno_backend.billing.controllers.CheckoutController;
import org.tornotron.echno_backend.billing.controllers.SubscriptionController;
import org.tornotron.echno_backend.common.controller.KeycloakGroupController;
import org.tornotron.echno_backend.common.module.web.ModuleControllerWeb;

/**
 * Pins the access matrix action each handler of the organization's administration and profile
 * needs: the organization endpoints on both paths, the subscription, checkout and billing history,
 * group and role assignment, and the modules. A handler that names the organization in its path
 * also checks that it is the organization the request is for (marked {@code #id}).
 *
 * <p>The platform administrator's handlers, the open ones and the ones shut with {@code denyAll()}
 * are not organization-scoped and keep their own guards, so only handlers asking {@code @access}
 * are listed.
 */
class OrgAdminGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them; valued by area and action. */
    static final Map<String, String> PERMISSION_BY_HANDLER = Map.ofEntries(
            Map.entry("SubscriptionController#getCurrentSubscription", "org-admin:read"),
            Map.entry("SubscriptionController#getSubscriptionHistory", "org-admin:read"),
            Map.entry("SubscriptionController#createSubscription", "org-admin:create"),
            Map.entry("SubscriptionController#changeSubscription", "org-admin:edit"),
            Map.entry("SubscriptionController#cancelSubscription", "org-admin:create"),
            Map.entry("SubscriptionController#checkFeatureAccess", "org-profile:read"),
            Map.entry("SubscriptionController#recordUsage", "org-profile:create"),
            Map.entry("CheckoutController#getProvider", "org-profile:read"),
            Map.entry("CheckoutController#createSession", "org-admin:create"),
            Map.entry("CheckoutController#verify", "org-admin:approve"),
            Map.entry("CheckoutController#getMandate", "org-admin:read"),
            Map.entry("CheckoutController#acknowledgeMandate", "org-admin:create"),
            Map.entry("BillingHistoryController#listEvents", "org-admin:read"),
            Map.entry("KeycloakGroupController#assignUserToKeycloakOrg", "org-admin:create"),
            Map.entry("KeycloakGroupController#assignOrgRole", "org-admin:create"),
            Map.entry("KeycloakGroupController#unassignOrgRole", "org-admin:create"),
            Map.entry("ModuleControllerWeb#installed", "org-admin:read"),
            Map.entry("ModuleControllerWeb#enabled", "org-profile:read"),
            Map.entry("OrganizationController#readAnOrganization", "#id org-profile:read"),
            Map.entry("OrganizationController#deleteOrganization", "#id org-admin:delete"),
            Map.entry("OrganizationWebController#readAnOrganization", "#id org-profile:read"),
            Map.entry("OrganizationWebController#partialUpdateAnOrganization", "#id org-profile:edit"),
            Map.entry("OrganizationWebController#readDatasetConsent", "#id org-admin:read"),
            Map.entry("OrganizationWebController#setDatasetConsent", "#id org-admin:edit"),
            Map.entry("OrganizationWebController#deleteOrganization", "#id org-admin:delete"));

    private static final String ON_THE_PATH = "@orgSecurity.isCurrentTenant(#id) and ";

    @Test
    void everyHandlerNeedsTheActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(OrgAdminGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(PERMISSION_BY_HANDLER.keySet());
        PERMISSION_BY_HANDLER.forEach((handler, permission) -> {
            boolean onThePath = permission.startsWith("#id ");
            String[] parts = permission.replace("#id ", "").split(":");
            assertThat(guards.get(handler)).as(handler).isEqualTo(
                    (onThePath ? ON_THE_PATH : "") + "@access.can('" + parts[0] + "', '" + parts[1] + "')");
        });
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    /** The handlers that ask {@code @access}. */
    static List<Method> handlers() {
        return Stream.of(SubscriptionController.class, CheckoutController.class, BillingHistoryController.class, KeycloakGroupController.class, ModuleControllerWeb.class, OrganizationController.class, OrganizationWebController.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .filter(method -> method.isAnnotationPresent(PreAuthorize.class)
                        && method.getAnnotation(PreAuthorize.class).value().contains("@access."))
                .toList();
    }
}
