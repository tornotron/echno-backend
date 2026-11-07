package org.tornotron.echno_backend.modules.access.matrix.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;

/**
 * A role's own rules about its grants: no duplicates, and revoking removes one permission and
 * scope.
 */
class AccessRoleTest {

    private static final AccessPermission READ_LEAVE = AccessPermission.of("leave:read");

    @Test
    void grantingTheSamePermissionAndScopeTwiceKeepsOneRow() {
        AccessRole role = new AccessRole();

        role.grant(READ_LEAVE, AccessScope.OWN);
        role.grant(READ_LEAVE, AccessScope.OWN);
        role.grant(READ_LEAVE, AccessScope.ALL);

        assertThat(role.getGrants()).extracting(AccessRoleGrant::getScope)
                .containsExactly(AccessScope.OWN, AccessScope.ALL);
        assertThat(role.getGrants()).allSatisfy(g -> assertThat(g.getRole()).isSameAs(role));
    }

    @Test
    void revokingRemovesThatPermissionAndScopeOnly() {
        AccessRole role = new AccessRole();
        role.grant(READ_LEAVE, AccessScope.OWN);
        role.grant(READ_LEAVE, AccessScope.ALL);

        assertThat(role.revoke(READ_LEAVE, AccessScope.ALL)).isTrue();
        assertThat(role.revoke(READ_LEAVE, AccessScope.ALL)).isFalse();
        assertThat(role.getGrants()).extracting(AccessRoleGrant::getScope).containsExactly(AccessScope.OWN);
    }
}
