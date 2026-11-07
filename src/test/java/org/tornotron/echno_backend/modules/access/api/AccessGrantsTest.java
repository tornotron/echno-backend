package org.tornotron.echno_backend.modules.access.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What a person holds, answered by permission.
 */
class AccessGrantsTest {

    private static final AccessPermission READ = AccessPermission.of("finance:read");
    private static final AccessPermission EDIT = AccessPermission.of("finance:edit");

    @Test
    void dropsEmptyEntriesAndAnswersByPermission() {
        AccessGrants grants = new AccessGrants(
                Map.of("finance:read", Set.of(AccessScope.ALL), "finance:edit", Set.of()),
                Map.of());

        assertThat(grants.holds(READ)).isTrue();
        assertThat(grants.holds(EDIT)).isFalse();
        assertThat(grants.scopes()).containsOnlyKeys("finance:read");
        assertThat(grants.scopesFor(EDIT)).isEmpty();
    }

    @Test
    void aGrantOnAProjectIsHeldButIsNotAnOrganizationWideScope() {
        AccessGrants grants = new AccessGrants(Map.of(),
                Map.of(100L, Map.of("finance:read", Set.of(AccessScope.ALL)), 200L, Map.of()));

        assertThat(grants.holds(READ)).isTrue();
        assertThat(grants.scopesFor(READ)).isEmpty();
        assertThat(grants.scopesOn(100L, READ)).containsExactly(AccessScope.ALL);
        assertThat(grants.scopesOn(200L, READ)).isEmpty();
        assertThat(grants.projectScopes()).containsOnlyKeys(100L);
    }

    @Test
    void noneHoldsNothing() {
        assertThat(AccessGrants.none().scopes()).isEmpty();
        assertThat(AccessGrants.none().projectScopes()).isEmpty();
        assertThat(AccessGrants.none().holds(READ)).isFalse();
    }
}
