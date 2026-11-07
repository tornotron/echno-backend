package org.tornotron.echno_backend.modules.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.tornotron.echno_backend.common.module.ModuleEntitlementResolver;
import org.tornotron.echno_backend.common.module.ModuleManifest;
import org.tornotron.echno_backend.common.module.ModuleRegistry;

/**
 * The module's contract with the registry: free, and on for every organization.
 */
class AccessControlModuleTest {

    private final AccessControlModule module = new AccessControlModule();

    @Test
    void manifestIsTheFreeAccessModule() {
        ModuleManifest manifest = module.manifest();

        assertThat(manifest.id()).isEqualTo("access");
        assertThat(manifest.isPaywalled()).isFalse();
        assertThat(manifest.enabledByDefault()).isTrue();
        assertThat(manifest.dependsOn()).isEmpty();
        assertThat(manifest.navDescriptors()).isEmpty();
        assertThat(manifest.permissions())
                .containsExactly("access:read", "access:edit", "access:edit-employee-role")
                .allMatch(ModuleManifest.PERMISSION_KEY.asMatchPredicate());
    }

    @Test
    void isOnForEveryOrganizationWithoutAskingBilling() {
        ModuleEntitlementResolver refusesEverything = (org, key) -> {
            throw new AssertionError("access control must not consult billing");
        };
        ModuleRegistry registry = new ModuleRegistry(List.of(module), refusesEverything, new MockEnvironment());

        assertThat(registry.isEnabledForOrg("access", 7L)).isTrue();
    }
}
