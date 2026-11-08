package org.tornotron.echno_backend.modules.vision;

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
import org.tornotron.echno_backend.common.module.ModuleManifest;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.modules.inspections.InspectionsModule;

/**
 * The module's contract with the registry, and the property that matters most: under the default
 * configuration the module is off. {@code application.yml} is read for real, since that file is
 * what makes the registry's answer false, and the resolver answers yes in the off cases because
 * that is what it does for a missing grant in the default advisory entitlement mode.
 */
class VisionModuleTest {

    private static final long ORG = 7L;

    private final VisionModule module = new VisionModule();

    @Test
    void manifestIsThePaywalledVisionModule() {
        ModuleManifest manifest = module.manifest();

        assertThat(manifest.id()).isEqualTo("vision");
        assertThat(manifest.name()).isEqualTo("Computer Vision");
        assertThat(manifest.entitlementFeatureKey()).isEqualTo("MODULE_VISION");
        assertThat(manifest.isPaywalled()).isTrue();
        assertThat(manifest.enabledByDefault()).isFalse();
        assertThat(manifest.dependsOn()).containsExactly("inspections");
        assertThat(manifest.navDescriptors()).isEmpty();
    }

    @Test
    void permissionsUseTheColonVocabulary() {
        assertThat(module.manifest().permissions())
                .containsExactly("vision:read", "vision:run", "vision:models-manage")
                .allMatch(ModuleManifest.PERMISSION_KEY.asMatchPredicate());
    }

    @Test
    void theKillSwitchIsOffUnderTheDefaultConfiguration() throws IOException {
        ModuleRegistry registry = registry(defaults());

        assertThat(VisionModuleEnabled.PROPERTY).isEqualTo("echno.modules.vision.enabled");
        assertThat(registry.isSwitchedOn("vision")).isFalse();
        assertThat(registry.isEntitled("vision", ORG)).as("advisory mode lets a refused feature through").isTrue();
        assertThat(registry.isEnabledForOrg("vision", ORG)).isFalse();
        assertThat(registry.enabledForOrg(ORG)).extracting(ModuleManifest::id).containsExactly("inspections");
    }

    @Test
    void anOperatorCanTurnTheModuleOn() throws IOException {
        ConfigurableEnvironment environment = defaults();
        environment.getPropertySources().addFirst(
                new MapPropertySource("operator", Map.of(VisionModuleEnabled.PROPERTY, "true")));

        assertThat(registry(environment).isEnabledForOrg("vision", ORG)).isTrue();
    }

    @Test
    void aBeanCarryingTheConditionDoesNotExistUnderTheDefaultConfiguration() {
        new ApplicationContextRunner()
                .withInitializer(VisionModuleTest::loadApplicationYml)
                .withUserConfiguration(SwitchedBean.class)
                .run(context -> assertThat(context).doesNotHaveBean(SwitchedBean.class));
    }

    @Test
    void aBeanCarryingTheConditionExistsWhenSwitchedOn() {
        new ApplicationContextRunner()
                .withInitializer(VisionModuleTest::loadApplicationYml)
                .withPropertyValues(VisionModuleEnabled.PROPERTY + "=true")
                .withUserConfiguration(SwitchedBean.class)
                .run(context -> assertThat(context).hasSingleBean(SwitchedBean.class));
    }

    @Test
    void aMissingPropertyIsAlsoOff() {
        new ApplicationContextRunner()
                .withUserConfiguration(SwitchedBean.class)
                .run(context -> assertThat(context).doesNotHaveBean(SwitchedBean.class));
    }

    private ModuleRegistry registry(ConfigurableEnvironment environment) {
        return new ModuleRegistry(List.of(new InspectionsModule(), module), (org, key) -> true, environment);
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
    @VisionModuleEnabled
    static class SwitchedBean {
    }
}
