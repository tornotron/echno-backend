package org.tornotron.echno_backend.modules.toolboxtalks;

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
class ToolboxTalksModuleTest {

    private static final long ORG = 7L;

    private final ToolboxTalksModule module = new ToolboxTalksModule();

    @Test
    void manifestIsThePaywalledToolboxTalksModule() {
        ModuleManifest manifest = module.manifest();

        assertThat(manifest.id()).isEqualTo("toolbox-talks");
        assertThat(manifest.name()).isEqualTo("Toolbox Talks");
        assertThat(manifest.version()).isEqualTo("0.1.0");
        assertThat(manifest.entitlementFeatureKey()).isEqualTo("MODULE_TOOLBOX_TALKS");
        assertThat(manifest.isPaywalled()).isTrue();
        assertThat(manifest.enabledByDefault()).isFalse();
        assertThat(manifest.dependsOn()).isEmpty();
        assertThat(manifest).isSameAs(module.manifest());
    }

    @Test
    void permissionsUseTheColonVocabulary() {
        assertThat(module.manifest().permissions())
                .containsExactly("toolbox-talks:read", "toolbox-talks:manage")
                .allMatch(ModuleManifest.PERMISSION_KEY.asMatchPredicate());
    }

    @Test
    void publishesOneNavEntryGatedOnRead() {
        assertThat(module.manifest().navDescriptors()).singleElement().satisfies(nav -> {
            assertThat(nav.section()).isEqualTo("toolbox-talks");
            assertThat(nav.path()).isEqualTo("/users/dashboard/toolbox-talks");
            assertThat(nav.requiredPermissions()).containsExactly("toolbox-talks:read");
        });
    }

    @Test
    void registersAndFollowsEntitlement() {
        ModuleRegistry entitled = new ModuleRegistry(List.of(module), (org, key) -> true, new MockEnvironment());
        ModuleRegistry dark = new ModuleRegistry(List.of(module), (org, key) -> false, new MockEnvironment());

        assertThat(entitled.installed()).extracting(ModuleManifest::id).containsExactly("toolbox-talks");
        assertThat(entitled.isEnabledForOrg("toolbox-talks", ORG)).isTrue();
        assertThat(dark.isEnabledForOrg("toolbox-talks", ORG)).isFalse();
        assertThat(entitled.navDescriptors("toolbox-talks")).extracting(NavDescriptor::path)
                .containsExactly("/users/dashboard/toolbox-talks");
    }

    @Test
    void killSwitchPropertyMatchesTheRegistryConvention() {
        MockEnvironment killed = new MockEnvironment().withProperty(ToolboxTalksModuleEnabled.PROPERTY, "false");
        ModuleRegistry registry = new ModuleRegistry(List.of(module), (org, key) -> true, killed);

        assertThat(ToolboxTalksModuleEnabled.PROPERTY).isEqualTo("echno.modules.toolbox-talks.enabled");
        assertThat(registry.isEnabledForOrg("toolbox-talks", ORG)).isFalse();
    }
}
