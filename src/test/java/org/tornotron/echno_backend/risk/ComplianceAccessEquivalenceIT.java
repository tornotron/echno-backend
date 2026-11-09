package org.tornotron.echno_backend.risk;

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
 * Moving the risk register and the document reversals, on the tenant API and its {@code /web} twin,
 * onto the access matrix changes nobody's access (design note 2.5): every member still reads both,
 * asks for a reversal and withdraws one, through the baseline, and a starting role records or
 * changes a risk, or decides a reversal, exactly when its old role did: the system admin and the
 * project manager. The baseline no longer holds compliance:approve, which would have let every
 * member decide a reversal.
 *
 * <p>Roles are given the way the old screens give them, through the assignment writer, so its legacy
 * mirror writes the {@code employee_org_roles} row the old checks read in the same transaction.
 * Neither area is scope aware, so the check is on the area alone.
 */
class ComplianceAccessEquivalenceIT extends RoleAssignmentITSupport {

    /** Any member of the organization, whatever role they hold. */
    private static final Set<String> MEMBERS = Set.of("any member");
    private static final Set<String> ADMIN_OR_PM = Set.of("system-admin", "project-manager");

    /** Each handler's guard before the move, by controller and handler. */
    private static final Map<String, Set<String>> OLD_GUARDS = Map.ofEntries(
            entry("ProjectRiskController#readRisks", MEMBERS),
            entry("ProjectRiskController#readRisk", MEMBERS),
            entry("ProjectRiskController#createRisk", ADMIN_OR_PM),
            entry("ProjectRiskController#importRisks", ADMIN_OR_PM),
            entry("ProjectRiskController#updateRisk", ADMIN_OR_PM),
            entry("ProjectRiskController#deleteRisk", ADMIN_OR_PM),
            entry("ProjectRiskControllerWeb#readRisks", MEMBERS),
            entry("ProjectRiskControllerWeb#readRisk", MEMBERS),
            entry("ProjectRiskControllerWeb#createRisk", ADMIN_OR_PM),
            entry("ProjectRiskControllerWeb#importRisks", ADMIN_OR_PM),
            entry("ProjectRiskControllerWeb#updateRisk", ADMIN_OR_PM),
            entry("ProjectRiskControllerWeb#deleteRisk", ADMIN_OR_PM),
            entry("DocumentReversalController#requestReversal", MEMBERS),
            entry("DocumentReversalController#listReversals", MEMBERS),
            entry("DocumentReversalController#listReversalsByDocument", MEMBERS),
            entry("DocumentReversalController#eligibility", MEMBERS),
            entry("DocumentReversalController#getReversal", MEMBERS),
            entry("DocumentReversalController#approveReversal", ADMIN_OR_PM),
            entry("DocumentReversalController#rejectReversal", ADMIN_OR_PM),
            entry("DocumentReversalController#cancelReversal", MEMBERS),
            entry("DocumentReversalControllerWeb#requestReversal", MEMBERS),
            entry("DocumentReversalControllerWeb#listReversals", MEMBERS),
            entry("DocumentReversalControllerWeb#listReversalsByDocument", MEMBERS),
            entry("DocumentReversalControllerWeb#eligibility", MEMBERS),
            entry("DocumentReversalControllerWeb#getReversal", MEMBERS),
            entry("DocumentReversalControllerWeb#approveReversal", ADMIN_OR_PM),
            entry("DocumentReversalControllerWeb#rejectReversal", ADMIN_OR_PM),
            entry("DocumentReversalControllerWeb#cancelReversal", MEMBERS));

    @Autowired
    private AccessService access;

    @BeforeEach
    void startingRoles() {
        reconciler.ensureStartingRoles(organization.getId());
        flush();
    }

    @Test
    void everyHandlerHasItsOldGuardRecorded() {
        assertThat(ComplianceGuardsTest.handlers()).extracting(ComplianceGuardsTest::key)
                .containsExactlyInAnyOrderElementsOf(OLD_GUARDS.keySet());
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesExactlyWhatItsOldRoleDid(OrgRole role) {
        Employee person = employee("compliance-eq-" + role.getGroupName());
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
        Employee person = employee("compliance-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    @Test
    void theBaselineAloneReachesWhatAnyMemberDid() {
        Employee person = employee("compliance-eq-baseline");

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldGuardsGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldGuardsGave(Employee person, Set<String> oldRoles) {
        flush();
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        for (Method handler : ComplianceGuardsTest.handlers()) {
            String key = ComplianceGuardsTest.key(handler);
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
