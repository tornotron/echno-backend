package org.tornotron.echno_backend.modules.access.matrix;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;
import org.tornotron.echno_backend.modules.access.matrix.assignment.RoleAssignmentITSupport;

/**
 * The service checks that asked for a role by name, each against the permission that replaced it:
 * every starting role reaches a check exactly when its old role was in the check's list.
 */
class ServiceRoleChecksEquivalenceIT extends RoleAssignmentITSupport {

    /** Each check's permission now, and the roles it named before. */
    private static final Map<String, Set<String>> OLD_ROLES = Map.ofEntries(
            // SelfApprovalPolicy.BREAK_GLASS_ROLE and ContractBillService.approve's own-certification check.
            entry("org-admin:approve-own-documents", Set.of("system-admin")),
            // PaymentAuthorizers' SETUP_ROLES, which said whether the reader may change the authorizer.
            entry("progress-bills:edit", Set.of("system-admin", "project-manager")),
            // PurchaseOrderEditPolicy.APPROVER_ROLE.
            entry("purchase-orders:approve", Set.of("system-admin")),
            // ProjectService.requireMayCompleteByHand.
            entry("projects:complete", Set.of("system-admin")),
            // DetectionVisibility's detection-read-roles.
            entry("inspections:read-detections", Set.of("detection-reviewer")));

    /** The checks over everyone's records, asked as a grant across the organization. */
    private static final Set<String> RECORD_MANAGERS = Set.of("system-admin", "hr-admin", "project-manager");
    private static final Map<String, Set<String>> OLD_ROLES_ACROSS = Map.ofEntries(
            // EmployeePatchFieldScope.PERSONNEL_ROLES.
            entry("team:edit", Set.of("system-admin", "hr-admin")),
            // AttendanceSecurityService's record-management roles: canManageRecords, canDecideApproval,
            // and the role half of canViewEmployeeRecords and canRecordFor.
            entry("team:approve", RECORD_MANAGERS),
            entry("team:read", RECORD_MANAGERS),
            entry("team:create", RECORD_MANAGERS));

    /** The self half of canViewEmployeeRecords and canRecordFor: every member on their own record. */
    private static final Set<String> OWN_RECORD = Set.of("team:read", "team:create");

    @Autowired
    private AccessService access;

    @BeforeEach
    void startingRoles() {
        reconciler.ensureStartingRoles(organization.getId());
        flush();
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void aStartingRoleReachesEachCheckExactlyWhenItsOldRoleDid(OrgRole role) {
        Employee person = employee("checks-eq-" + role.getGroupName());
        assignments.assignOrgRoleAsSystem(person.getId(), role);

        Set<String> oldRoles = legacyRowsOf(person).stream()
                .map(name -> OrgRole.valueOf(name).getGroupName())
                .collect(Collectors.toSet());
        assertThat(oldRoles).containsExactly(role.getGroupName());
        assertReachesExactlyWhatTheOldChecksGave(person, oldRoles);
    }

    @ParameterizedTest
    @ValueSource(strings = {"management-director", "procurement-manager", "finance-manager", "client-owner"})
    void aStartingRoleWithNoCounterpartReachesNone(String slug) {
        Employee person = employee("checks-eq-" + slug);
        assignments.assignAsSystem(person.getId(), roleId(slug), null);

        assertThat(legacyRowsOf(person)).isEmpty();
        assertReachesExactlyWhatTheOldChecksGave(person, Set.of());
    }

    @Test
    void theDetectionReviewerReachesWhatTheOldChecksGaveThatRoleName() {
        Employee person = employee("checks-eq-detection-reviewer");
        assignments.assignAsSystem(person.getId(), roleId("detection-reviewer"), null);

        assertThat(legacyRowsOf(person)).as("no old role stands behind it").isEmpty();
        assertReachesExactlyWhatTheOldChecksGave(person, Set.of("detection-reviewer"));
    }

    @Test
    void theBaselineAloneReachesNone() {
        Employee person = employee("checks-eq-baseline");

        assertReachesExactlyWhatTheOldChecksGave(person, Set.of());
    }

    private void assertReachesExactlyWhatTheOldChecksGave(Employee person, Set<String> oldRoles) {
        flush();
        AccessSubject subject = new AccessSubject(organization.getId(), person.getId(), person.getUser().getId());
        OLD_ROLES.forEach((key, roles) -> {
            boolean before = roles.stream().anyMatch(oldRoles::contains);
            assertThat(access.check(subject, AccessPermission.of(key))).as("%s for %s", key, oldRoles)
                    .isEqualTo(before);
        });
        OLD_ROLES_ACROSS.forEach((key, roles) -> {
            boolean before = roles.stream().anyMatch(oldRoles::contains);
            assertThat(access.filter(subject, AccessPermission.of(key)).everything())
                    .as("%s across the organization for %s", key, oldRoles).isEqualTo(before);
        });
        RecordFacts own = RecordFacts.of(organization.getId(), null).withOwners(Set.of(person.getId()));
        for (String key : OWN_RECORD) {
            assertThat(access.check(subject, AccessPermission.of(key), own))
                    .as("%s on their own record for %s", key, oldRoles).isTrue();
        }
    }
}
