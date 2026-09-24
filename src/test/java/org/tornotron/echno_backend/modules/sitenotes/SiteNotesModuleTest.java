package org.tornotron.echno_backend.modules.sitenotes;

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
class SiteNotesModuleTest {

    private static final long ORG = 7L;

    private final SiteNotesModule module = new SiteNotesModule();

    @Test
    void manifestIsThePaywalledSiteNotesModule() {
        ModuleManifest manifest = module.manifest();

        assertThat(manifest.id()).isEqualTo("site-notes");
        assertThat(manifest.name()).isEqualTo("Site Notes");
        assertThat(manifest.version()).isEqualTo("0.1.0");
        assertThat(manifest.entitlementFeatureKey()).isEqualTo("MODULE_SITE_NOTES");
        assertThat(manifest.isPaywalled()).isTrue();
        assertThat(manifest.enabledByDefault()).isFalse();
        assertThat(manifest.dependsOn()).isEmpty();
        assertThat(manifest).isSameAs(module.manifest());
    }

    @Test
    void permissionsUseTheColonVocabulary() {
        assertThat(module.manifest().permissions())
                .containsExactly("site-notes:read", "site-notes:manage")
                .allMatch(ModuleManifest.PERMISSION_KEY.asMatchPredicate());
    }

    @Test
    void publishesOneNavEntryGatedOnRead() {
        assertThat(module.manifest().navDescriptors()).singleElement().satisfies(nav -> {
            assertThat(nav.section()).isEqualTo("site-notes");
            assertThat(nav.path()).isEqualTo("/users/dashboard/site-notes");
            assertThat(nav.requiredPermissions()).containsExactly("site-notes:read");
        });
    }

    @Test
    void registersAndFollowsEntitlement() {
        ModuleRegistry entitled = new ModuleRegistry(List.of(module), (org, key) -> true, new MockEnvironment());
        ModuleRegistry dark = new ModuleRegistry(List.of(module), (org, key) -> false, new MockEnvironment());

        assertThat(entitled.installed()).extracting(ModuleManifest::id).containsExactly("site-notes");
        assertThat(entitled.isEnabledForOrg("site-notes", ORG)).isTrue();
        assertThat(dark.isEnabledForOrg("site-notes", ORG)).isFalse();
        assertThat(entitled.navDescriptors("site-notes")).extracting(NavDescriptor::path)
                .containsExactly("/users/dashboard/site-notes");
    }

    @Test
    void killSwitchPropertyMatchesTheRegistryConvention() {
        MockEnvironment killed = new MockEnvironment().withProperty(SiteNotesModuleEnabled.PROPERTY, "false");
        ModuleRegistry registry = new ModuleRegistry(List.of(module), (org, key) -> true, killed);

        assertThat(SiteNotesModuleEnabled.PROPERTY).isEqualTo("echno.modules.site-notes.enabled");
        assertThat(registry.isEnabledForOrg("site-notes", ORG)).isFalse();
    }
}
