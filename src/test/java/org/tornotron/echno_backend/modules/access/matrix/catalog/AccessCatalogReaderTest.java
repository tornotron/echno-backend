package org.tornotron.echno_backend.modules.access.matrix.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.tornotron.echno_backend.common.module.EchnoModule;
import org.tornotron.echno_backend.common.module.ModuleManifest;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.modules.access.AccessControlModule;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.guard.EnforcedPermissions;
import org.tornotron.echno_backend.modules.access.matrix.catalog.AccessCatalogReader.ActionEntry;
import org.tornotron.echno_backend.modules.access.matrix.catalog.AccessCatalogReader.AreaEntry;

/**
 * The catalog merges the static areas with what manifests declare, and the enforced flag follows
 * the guards, not the declarations.
 */
class AccessCatalogReaderTest {

    private final EnforcedPermissions enforced = mock(EnforcedPermissions.class);

    private static EchnoModule module(String id, List<String> permissions) {
        ModuleManifest manifest = new ModuleManifest(id, id, "0.1.0", null, List.of(), permissions, List.of(), true);
        return () -> manifest;
    }

    private final ModuleRegistry registry = new ModuleRegistry(
            List.of(new AccessControlModule(), module("work-progress", List.of("work-progress:read"))),
            (org, key) -> true, new MockEnvironment());

    private final AccessCatalogReader reader = new AccessCatalogReader(registry, enforced);

    private AreaEntry area(String id) {
        return reader.areas().stream().filter(area -> area.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void aManifestsNamedActionJoinsItsAreaInTheCatalog() {
        assertThat(area("access").actions()).extracting(ActionEntry::action)
                .containsExactly("read", "create", "edit", "approve", "delete", "edit-employee-role");
        assertThat(area("access").section()).isEqualTo("Setup");
    }

    @Test
    void aModuleTheCatalogDoesNotListGetsItsOwnArea() {
        assertThat(area("work-progress").section()).isEqualTo(AccessCatalogReader.MODULES_SECTION);
        assertThat(area("work-progress").actions()).extracting(ActionEntry::action).contains("read");
    }

    @Test
    void nothingIsEnforcedUntilAGuardChecksIt() {
        assertThat(reader.areas()).flatExtracting(AreaEntry::actions)
                .extracting(ActionEntry::enforced)
                .containsOnly(false);
    }

    @Test
    void theEnforcedFlagFollowsTheGuards() {
        when(enforced.isEnforced(AccessPermission.of("finance:read"))).thenReturn(true);

        assertThat(area("finance").actions())
                .filteredOn(ActionEntry::enforced)
                .extracting(ActionEntry::action)
                .containsExactly("read");
    }

    @Test
    void declaresWhatTheCatalogOrAManifestLists() {
        assertThat(reader.declares(AccessPermission.of("finance:read"))).isTrue();
        assertThat(reader.declares(AccessPermission.of("access:edit-employee-role"))).isTrue();
        assertThat(reader.declares(AccessPermission.of("finance:edit-employee-role"))).isFalse();
        assertThat(reader.declares(AccessPermission.of("harbour:read"))).isFalse();
    }
}
