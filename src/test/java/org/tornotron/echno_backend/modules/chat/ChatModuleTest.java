package org.tornotron.echno_backend.modules.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.tornotron.echno_backend.common.module.ModuleManifest;
import org.tornotron.echno_backend.common.module.ModuleRegistry;

/**
 * The module's contract with the registry: an optional, free module that is on for every
 * organization and can be switched off, and the permission vocabulary the guards use.
 */
class ChatModuleTest {

    private final ChatModule module = new ChatModule();

    @Test
    void manifestIsTheOptionalFreeChatModule() {
        ModuleManifest manifest = module.manifest();

        assertThat(manifest.id()).isEqualTo("chat");
        assertThat(manifest.core()).isFalse();
        assertThat(manifest.isPaywalled()).isFalse();
        assertThat(manifest.enabledByDefault()).isTrue();
        assertThat(manifest.dependsOn()).isEmpty();
        assertThat(manifest.permissions()).containsExactly("chat:view", "chat:create")
                .allMatch(ModuleManifest.PERMISSION_KEY.asMatchPredicate());
    }

    @Test
    void isOnForEveryOrganizationWithoutAskingBillingAndCanBeSwitchedOff() {
        ModuleRegistry everyone = new ModuleRegistry(List.of(module), (org, key) -> {
            throw new AssertionError("a free module must not consult billing");
        }, new MockEnvironment());
        ModuleRegistry switchedOff = new ModuleRegistry(List.of(module), (org, key) -> true,
                new MockEnvironment().withProperty("echno.modules.chat.enabled", "false"));

        assertThat(everyone.isEnabledForOrg("chat", 7L)).isTrue();
        assertThat(switchedOff.isEnabledForOrg("chat", 7L)).isFalse();
    }
}
