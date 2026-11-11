package org.tornotron.echno_backend.modules.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.tornotron.echno_backend.common.module.ModuleManifest;
import org.tornotron.echno_backend.common.module.ModuleRegistry;

/**
 * The module's contract with the registry, the permission vocabulary, and the dark-by-default
 * guarantee: under the default configuration the module is off even in advisory entitlement mode.
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
    void registersAndFollowsEntitlementWhenSwitchedOn() {
        MockEnvironment on = new MockEnvironment().withProperty(AssistantModuleEnabled.PROPERTY, "true");
        ModuleRegistry entitled = new ModuleRegistry(List.of(module), (org, key) -> true, on);
        ModuleRegistry dark = new ModuleRegistry(List.of(module), (org, key) -> false, on);

        assertThat(entitled.installed()).extracting(ModuleManifest::id).containsExactly("assistant");
        assertThat(entitled.isEnabledForOrg("assistant", ORG)).isTrue();
        assertThat(dark.isEnabledForOrg("assistant", ORG)).isFalse();
    }

    @Test
    void isOffWhenSwitchPropertyIsMissingEvenIfEntitled() {
        ModuleRegistry registry = new ModuleRegistry(List.of(module), (org, key) -> true, new MockEnvironment());

        assertThat(registry.isSwitchedOn("assistant")).isFalse();
        assertThat(registry.isEnabledForOrg("assistant", ORG)).isFalse();
    }

    @Test
    void theKillSwitchIsOffUnderTheDefaultConfiguration() throws IOException {
        ModuleRegistry registry = new ModuleRegistry(List.of(module), (org, key) -> true, defaults());

        assertThat(AssistantModuleEnabled.PROPERTY).isEqualTo("echno.modules.assistant.enabled");
        assertThat(registry.isSwitchedOn("assistant")).isFalse();
        assertThat(registry.isEntitled("assistant", ORG)).as("advisory mode lets a refused feature through").isTrue();
        assertThat(registry.isEnabledForOrg("assistant", ORG)).isFalse();
    }

    @Test
    void anOperatorCanTurnTheModuleOnOverDefaultConfiguration() throws IOException {
        ConfigurableEnvironment environment = defaults();
        environment.getPropertySources().addFirst(
                new MapPropertySource("operator", Map.of(AssistantModuleEnabled.PROPERTY, "true")));
        ModuleRegistry registry = new ModuleRegistry(List.of(module), (org, key) -> true, environment);

        assertThat(registry.isEnabledForOrg("assistant", ORG)).isTrue();
    }

    @Test
    void aBeanCarryingTheConditionDoesNotExistUnderTheDefaultConfiguration() {
        new ApplicationContextRunner()
                .withInitializer(AssistantModuleTest::loadApplicationYml)
                .withUserConfiguration(SwitchedBean.class)
                .run(context -> assertThat(context).doesNotHaveBean(SwitchedBean.class));
    }

    @Test
    void aBeanCarryingTheConditionExistsWhenSwitchedOn() {
        new ApplicationContextRunner()
                .withInitializer(AssistantModuleTest::loadApplicationYml)
                .withPropertyValues(AssistantModuleEnabled.PROPERTY + "=true")
                .withUserConfiguration(SwitchedBean.class)
                .run(context -> assertThat(context).hasSingleBean(SwitchedBean.class));
    }

    @Test
    void aMissingPropertyLeavesConditionalBeansOff() {
        new ApplicationContextRunner()
                .withUserConfiguration(SwitchedBean.class)
                .run(context -> assertThat(context).doesNotHaveBean(SwitchedBean.class));
    }

    private static ConfigurableEnvironment defaults() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        addApplicationYml(environment);
        return environment;
    }

    private static void loadApplicationYml(ConfigurableApplicationContext context) {
        try {
            addApplicationYml(context.getEnvironment());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void addApplicationYml(ConfigurableEnvironment environment) throws IOException {
        new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
    }

    @Configuration(proxyBeanMethods = false)
    @AssistantModuleEnabled
    static class SwitchedBean {
    }
}
