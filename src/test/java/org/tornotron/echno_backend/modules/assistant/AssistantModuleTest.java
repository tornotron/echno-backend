package org.tornotron.echno_backend.modules.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.tornotron.echno_backend.common.module.ModuleManifest;
import org.tornotron.echno_backend.common.module.ModuleRegistry;

/**
 * The module's contract with the registry: the manifest, the permission vocabulary, that it
 * publishes no nav entry yet, and that the kill switch property is the one the registry reads.
 */
class AssistantModuleTest {

    private static final long ORG = 7L;

    private final AssistantModule module = new AssistantModule();

    @Test
    void manifestIsThePaywalledAssistantModule() {
        ModuleManifest manifest = module.manifest();

        assertThat(manifest.id()).isEqualTo("assistant");
        assertThat(manifest.name()).isEqualTo("Assistant");
        assertThat(manifest.version()).isEqualTo("0.1.0");
        assertThat(manifest.entitlementFeatureKey()).isEqualTo("MODULE_ASSISTANT");
        assertThat(manifest.isPaywalled()).isTrue();
        assertThat(manifest.enabledByDefault()).isFalse();
        assertThat(manifest.dependsOn()).isEmpty();
        assertThat(manifest).isSameAs(module.manifest());
    }

    @Test
    void declaresTheThreePermissionsInTheColonVocabulary() {
        assertThat(module.manifest().permissions())
                .containsExactly("assistant:ask", "assistant:ingest", "assistant:admin")
                .allMatch(ModuleManifest.PERMISSION_KEY.asMatchPredicate());
    }

    @Test
    void publishesNoNavEntryUntilTheWebPanelExists() {
        assertThat(module.manifest().navDescriptors()).isEmpty();
    }

    @Test
    void registersAndFollowsEntitlement() {
        ModuleRegistry entitled = new ModuleRegistry(List.of(module), (org, key) -> true, new MockEnvironment());
        ModuleRegistry dark = new ModuleRegistry(List.of(module), (org, key) -> false, new MockEnvironment());

        assertThat(entitled.installed()).extracting(ModuleManifest::id).containsExactly("assistant");
        assertThat(entitled.isEnabledForOrg("assistant", ORG)).isTrue();
        assertThat(dark.isEnabledForOrg("assistant", ORG)).isFalse();
    }

    @Test
    void killSwitchPropertyMatchesTheRegistryConvention() {
        MockEnvironment killed = new MockEnvironment().withProperty(AssistantModuleEnabled.PROPERTY, "false");
        ModuleRegistry registry = new ModuleRegistry(List.of(module), (org, key) -> true, killed);

        assertThat(AssistantModuleEnabled.PROPERTY).isEqualTo("echno.modules.assistant.enabled");
        assertThat(registry.isEnabledForOrg("assistant", ORG)).isFalse();
    }
}
