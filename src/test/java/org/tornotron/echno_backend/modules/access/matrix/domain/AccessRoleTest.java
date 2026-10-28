package org.tornotron.echno_backend.modules.access.matrix.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;

/**
 * A role's own rules about its grants: no duplicates, and locked grants stay.
 */
class AccessRoleTest {

    private static final AccessPermission VIEW_LEAVE = AccessPermission.of("leave:view");

    @Test
    void grantingTheSamePermissionAndScopeTwiceKeepsOneRow() {
        AccessRole role = new AccessRole();

        role.grant(VIEW_LEAVE, AccessScope.OWN, false);
        role.grant(VIEW_LEAVE, AccessScope.OWN, false);
        role.grant(VIEW_LEAVE, AccessScope.ALL, false);

        assertThat(role.getGrants()).extracting(AccessRoleGrant::getScope)
                .containsExactly(AccessScope.OWN, AccessScope.ALL);
        assertThat(role.getGrants()).allSatisfy(g -> assertThat(g.getRole()).isSameAs(role));
    }

    @Test
    void aLockedGrantCannotBeRevoked() {
        AccessRole role = new AccessRole();
        role.grant(VIEW_LEAVE, AccessScope.OWN, true);
        role.grant(VIEW_LEAVE, AccessScope.ALL, false);

        assertThat(role.revoke(VIEW_LEAVE, AccessScope.OWN)).isFalse();
        assertThat(role.revoke(VIEW_LEAVE, AccessScope.ALL)).isTrue();
        assertThat(role.getGrants()).extracting(AccessRoleGrant::getScope).containsExactly(AccessScope.OWN);
    }
}
