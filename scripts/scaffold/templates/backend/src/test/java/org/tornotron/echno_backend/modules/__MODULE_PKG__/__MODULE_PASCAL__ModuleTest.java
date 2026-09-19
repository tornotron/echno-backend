package org.tornotron.echno_backend.modules.__MODULE_PKG__;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.tornotron.echno_backend.common.module.ModuleManifest;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.common.module.NavDescriptor;

/**
 * The module's contract with the registry: the manifest, the permission vocabulary, the nav
 * entry, and that the kill switch property is the one the registry reads.
 */
class __MODULE_PASCAL__ModuleTest {

    private static final long ORG = 7L;

    private final __MODULE_PASCAL__Module module = new __MODULE_PASCAL__Module();

    @Test
    void manifestIsThePaywalled__MODULE_PASCAL__Module() {
        ModuleManifest manifest = module.manifest();

        assertThat(manifest.id()).isEqualTo("__MODULE_ID__");
        assertThat(manifest.name()).isEqualTo("__MODULE_NAME__");
        assertThat(manifest.version()).isEqualTo("0.1.0");
        assertThat(manifest.entitlementFeatureKey()).isEqualTo("__FEATURE_KEY__");
        assertThat(manifest.isPaywalled()).isTrue();
        assertThat(manifest.enabledByDefault()).isFalse();
        assertThat(manifest.dependsOn()).isEmpty();
        assertThat(manifest).isSameAs(module.manifest());
    }

    @Test
    void permissionsUseTheColonVocabulary() {
        assertThat(module.manifest().permissions())
                .containsExactly("__MODULE_ID__:read", "__MODULE_ID__:manage")
                .allMatch(ModuleManifest.PERMISSION_KEY.asMatchPredicate());
    }

    @Test
    void publishesOneNavEntryGatedOnRead() {
        assertThat(module.manifest().navDescriptors()).singleElement().satisfies(nav -> {
            assertThat(nav.section()).isEqualTo("__MODULE_ID__");
            assertThat(nav.path()).isEqualTo("/users/dashboard/__MODULE_ID__");
            assertThat(nav.requiredPermissions()).containsExactly("__MODULE_ID__:read");
        });
    }

    @Test
    void registersAndFollowsEntitlement() {
        ModuleRegistry entitled = new ModuleRegistry(List.of(module), (org, key) -> true, new MockEnvironment());
        ModuleRegistry dark = new ModuleRegistry(List.of(module), (org, key) -> false, new MockEnvironment());

        assertThat(entitled.installed()).extracting(ModuleManifest::id).containsExactly("__MODULE_ID__");
        assertThat(entitled.isEnabledForOrg("__MODULE_ID__", ORG)).isTrue();
        assertThat(dark.isEnabledForOrg("__MODULE_ID__", ORG)).isFalse();
        assertThat(entitled.navDescriptors("__MODULE_ID__")).extracting(NavDescriptor::path)
                .containsExactly("/users/dashboard/__MODULE_ID__");
    }

    @Test
    void killSwitchPropertyMatchesTheRegistryConvention() {
        MockEnvironment killed = new MockEnvironment().withProperty(__MODULE_PASCAL__ModuleEnabled.PROPERTY, "false");
        ModuleRegistry registry = new ModuleRegistry(List.of(module), (org, key) -> true, killed);

        assertThat(__MODULE_PASCAL__ModuleEnabled.PROPERTY).isEqualTo("echno.modules.__MODULE_ID__.enabled");
        assertThat(registry.isEnabledForOrg("__MODULE_ID__", ORG)).isFalse();
    }
}
