package org.tornotron.echno_backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What {@link RoleGuardRatchetTest} counts as a role-name guard, on planted expressions: every guard
 * in the application passes the ratchet today, so this is what shows it bites.
 */
class RoleGuardRatchetPatternTest {

    @Test
    void theRuleBitesOnEachShapeAndSparesTheRest() {
        assertThat(List.of(
                "@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')",
                "@orgSecurity.hasOrgRole(#id, 'system-admin')",
                "@orgSecurity.isSelfOrHasAnyOrgRole(#id, 'system-admin', 'hr-admin')",
                "@access.can('team', 'read') or @orgSecurity.hasAnyOrgRole(#orgId, 'hr-admin')",
                "hasRole('system-admin')",
                "hasAnyRole('platform-admin', 'support')",
                "hasAuthority('leave:admin')",
                "@attendanceSecurity.canRecordFor(#employeeId)",
                "@inspectionSecurity.canIntakeObservations()"))
                .allMatch(RoleGuardRatchetTest::isRoleNameGuard);
        assertThat(List.of(
                "@access.can('vision', 'read')",
                "@access.isMember()",
                "@access.canIn(#id, 'org-admin', 'delete')",
                "hasRole('platform-admin')",
                "@orgSecurity.isSelfInCurrentTenant(#employeeId) or @leaveAccess.acrossOrganization('read')",
                "@orgSecurity.isSelfUser(#userId)",
                "@orgSecurity.isMemberOfCurrentTenant()",
                "isAuthenticated()",
                "permitAll()",
                "denyAll()"))
                .noneMatch(RoleGuardRatchetTest::isRoleNameGuard);
    }
}
