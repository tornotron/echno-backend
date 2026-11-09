package org.tornotron.echno_backend.organization;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.guard.AccessGuardExpressions;
import org.tornotron.echno_backend.modules.access.matrix.assignment.RoleAssignmentITSupport;

/**
 * Moving the organization's administration and profile onto the access matrix changes nobody's
 * access (design note 2.5): every member still reads the organization, its modules, its plan's
 * features and the checkout provider and records usage, through the baseline's org-profile read and
 * create; the system admin and HR admin change its details through org-profile:edit; and only the
 * system admin reaches the rest, through org-admin.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction.
 * Neither area is scope aware, so the check is on the area alone. The handlers that name the
 * organization in their path ask in that organization, which here is the caller's own.
 */
class OrgAdminAccessEquivalenceIT extends RoleAssignmentITSupport {

    /** Any member of the organization, whatever role they hold. */
    private static final Set<String> MEMBERS = Set.of("any member");
    private static final Set<String> SYSTEM_ADMIN = Set.of("system-admin");
    private static final Set<String> SYSTEM_OR_HR_ADMIN = Set.of("system-admin", "hr-admin");

    /** Each handler's guard before the move, by controller and handler. */
    private static final Map<String, Set<String>> OLD_GUARDS = Map.ofEntries(
            entry("SubscriptionController#getCurrentSubscription", SYSTEM_ADMIN),
            entry("SubscriptionController#getSubscriptionHistory", SYSTEM_ADMIN),
            entry("SubscriptionController#createSubscription", SYSTEM_ADMIN),
            entry("SubscriptionController#changeSubscription", SYSTEM_ADMIN),
            entry("SubscriptionController#cancelSubscription", SYSTEM_ADMIN),
            entry("SubscriptionController#checkFeatureAccess", MEMBERS),
            entry("SubscriptionController#recordUsage", MEMBERS),
            entry("CheckoutController#getProvider", MEMBERS),
            entry("CheckoutController#createSession", SYSTEM_ADMIN),
            entry("CheckoutController#verify", SYSTEM_ADMIN),
            entry("CheckoutController#getMandate", SYSTEM_ADMIN),
            entry("CheckoutController#acknowledgeMandate", SYSTEM_ADMIN),
            entry("BillingHistoryController#listEvents", SYSTEM_ADMIN),
            entry("KeycloakGroupController#assignUserToKeycloakOrg", SYSTEM_ADMIN),
            entry("KeycloakGroupController#assignOrgRole", SYSTEM_ADMIN),
            entry("KeycloakGroupController#unassignOrgRole", SYSTEM_ADMIN),
            entry("ModuleControllerWeb#installed", SYSTEM_ADMIN),
            entry("ModuleControllerWeb#enabled", MEMBERS),
            entry("OrganizationController#readAnOrganization", MEMBERS),
            entry("OrganizationController#deleteOrganization", SYSTEM_ADMIN),
            entry("OrganizationWebController#readAnOrganization", MEMBERS),
            entry("OrganizationWebController#partialUpdateAnOrganization", SYSTEM_OR_HR_ADMIN),
            entry("OrganizationWebController#readDatasetConsent", SYSTEM_ADMIN),
            entry("OrganizationWebController#setDatasetConsent", SYSTEM_ADMIN),
            entry("OrganizationWebController#deleteOrganization", SYSTEM_ADMIN));

    @Autowired
    private AccessService access;

    @BeforeEach
    void startingRoles() {
        reconciler.ensureStartingRoles(organization.getId());
        flush();
    }

    @Test
    void everyHandlerHasItsOldGuardRecorded() {
        assertThat(OrgAdminGuardsTest.handlers()).extracting(OrgAdminGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("org-eq-" + role.getGroupName());
        assignments.assignOrgRoleAsSystem(person.getId(), role);

        Set<String> oldRoles = legacyRowsOf(person).stream()
                .map(name -> OrgRole.valueOf(name).getGroupName())
                .collect(Collectors.toSet());
        assertThat(oldRoles).containsExactly(role.getGroupName());
        assertReachesExactlyWhatTheOldGuardsGave(person, oldRoles);
    }

    @ParameterizedTest
    @ValueSource(strings = {"management-director", "procurement-manager", "finance-manager", "client-owner"})
    void aStartingRoleWithNoCounterpartReachesWhatTheBaselineDoes(String slug) {
        Employee person = employee("org-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesWhatAnyMemberDid() {
        Employee person = employee("org-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        flush();
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        for (Method handler : OrgAdminGuardsTest.handlers()) {
            String key = OrgAdminGuardsTest.key(handler);
            Set<String> guard = OLD_GUARDS.get(key);
            boolean before = guard == MEMBERS || guard.stream().anyMatch(oldRoles::contains);

            assertThat(access.check(subject, permissionOf(handler)))
                    .as("%s for %s", key, oldRoles).isEqualTo(before);
        }
    }

    private static AccessPermission permissionOf(Method handler) {
        Set<AccessPermission> permissions = AccessGuardExpressions
                .parse(handler.getAnnotation(PreAuthorize.class).value()).permissions();
        assertThat(permissions).as(handler.getName()).hasSize(1);
        return permissions.iterator().next();
    }
}
