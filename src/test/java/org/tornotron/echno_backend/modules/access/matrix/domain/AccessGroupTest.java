package org.tornotron.echno_backend.modules.access.matrix.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;

/**
 * A group's own grants: no duplicates, and each one belongs to the group.
 */
class AccessGroupTest {

    private static final AccessPermission VIEW_FINANCE = AccessPermission.of("finance:view");

    @Test
    void grantingTheSamePermissionAndScopeTwiceKeepsOneRow() {
        AccessGroup group = new AccessGroup();

        group.grant(VIEW_FINANCE, AccessScope.ALL);
        group.grant(VIEW_FINANCE, AccessScope.ALL);

        assertThat(group.getGrants()).singleElement().satisfies(g -> {
            assertThat(g.getGroup()).isSameAs(group);
            assertThat(g.getPermissionKey()).isEqualTo("finance:view");
        });
    }

    @Test
    void revokingRemovesOnlyThatScope() {
        AccessGroup group = new AccessGroup();
        group.grant(VIEW_FINANCE, AccessScope.ALL);
        group.grant(VIEW_FINANCE, AccessScope.RELATED);

        assertThat(group.revoke(VIEW_FINANCE, AccessScope.ALL)).isTrue();
        assertThat(group.getGrants()).extracting(AccessGroupGrant::getScope).containsExactly(AccessScope.RELATED);
    }

    @Test
    void aNewGroupIsAPlainGroup() {
        assertThat(new AccessGroup().getKind()).isEqualTo(AccessGroupKind.GROUP);
    }
}
