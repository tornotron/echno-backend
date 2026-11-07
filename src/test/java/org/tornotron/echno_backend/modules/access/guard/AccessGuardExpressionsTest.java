package org.tornotron.echno_backend.modules.access.guard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;

/** Reading the permissions out of a guard, and telling a malformed one apart. */
class AccessGuardExpressionsTest {

    @Test
    void readsEveryLiteralInAnExpression() {
        AccessGuardExpressions.Parsed parsed = AccessGuardExpressions.parse(
                "@access.can('wbs', 'edit') or @access.can( 'finance','read' )");

        assertThat(parsed.malformed()).isEmpty();
        assertThat(parsed.permissions())
                .containsExactly(AccessPermission.of("wbs:edit"), AccessPermission.of("finance:read"));
    }

    @Test
    void otherGuardsNameNoPermission() {
        AccessGuardExpressions.Parsed parsed = AccessGuardExpressions.parse(
                "hasAuthority('finance:read') or @access.isMember() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('x')");

        assertThat(parsed.permissions()).isEmpty();
        assertThat(parsed.malformed()).isEmpty();
    }

    @Test
    void aBadKeyOrANonLiteralArgumentIsMalformed() {
        assertThat(AccessGuardExpressions.parse("@access.can('Finance', 'read')").malformed()).hasSize(1);
        assertThat(AccessGuardExpressions.parse("@access.can('finance', 'read:all')").malformed()).hasSize(1);
        assertThat(AccessGuardExpressions.parse("@access.can('finance')").malformed()).hasSize(1);
        assertThat(AccessGuardExpressions.parse("@access.can(#area, 'read')").malformed()).hasSize(1);
        assertThat(AccessGuardExpressions.parse("@access.can()").malformed()).hasSize(1);
    }
}
