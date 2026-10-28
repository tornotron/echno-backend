package org.tornotron.echno_backend.modules.access.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What a person holds, answered by permission.
 */
class AccessGrantsTest {

    @Test
    void dropsEmptyEntriesAndAnswersByPermission() {
        AccessGrants grants = new AccessGrants(Map.of(
                "finance:view", Set.of(AccessScope.ALL),
                "finance:edit", Set.of()));

        assertThat(grants.holds(AccessPermission.of("finance:view"))).isTrue();
        assertThat(grants.holds(AccessPermission.of("finance:edit"))).isFalse();
        assertThat(grants.scopes()).containsOnlyKeys("finance:view");
        assertThat(grants.scopesFor(AccessPermission.of("finance:edit"))).isEmpty();
    }

    @Test
    void noneHoldsNothing() {
        assertThat(AccessGrants.none().scopes()).isEmpty();
        assertThat(AccessGrants.none().holds(AccessPermission.of("finance:view"))).isFalse();
    }
}
