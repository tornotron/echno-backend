package org.tornotron.echno_backend.modules.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.tornotron.echno_backend.common.module.ModuleEntitlementResolver;
import org.tornotron.echno_backend.common.module.ModuleManifest;
import org.tornotron.echno_backend.common.module.ModuleRegistry;

/**
 * The module's contract with the registry: a core module that is on for every organization and
 * cannot be switched off.
 */
class AccessControlModuleTest {

    private final AccessControlModule module = new AccessControlModule();

    @Test
    void manifestIsTheCoreAccessModule() {
        ModuleManifest manifest = module.manifest();

        assertThat(manifest.id()).isEqualTo("access");
        assertThat(manifest.core()).isTrue();
        assertThat(manifest.isPaywalled()).isFalse();
        assertThat(manifest.dependsOn()).isEmpty();
        assertThat(manifest.navDescriptors()).isEmpty();
        assertThat(manifest.permissions()).containsExactly("access:view", "access:edit")
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

    @Test
    void cannotBeSwitchedOff() {
        MockEnvironment environment = new MockEnvironment().withProperty("echno.modules.access.enabled", "false");

        assertThatThrownBy(() -> new ModuleRegistry(List.of(module), (org, key) -> true, environment))
                .isInstanceOf(IllegalStateException.class);
    }
}
