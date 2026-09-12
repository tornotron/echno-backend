package org.tornotron.echno_backend.common.module;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The registry's boot-time validation and its two runtime switches, driven without a Spring
 * context: the registry takes its inputs through the constructor, so every case here is a plain
 * object test.
 */
class ModuleRegistryTest {

    private static final ModuleEntitlementResolver ENTITLED_TO_EVERYTHING = (org, key) -> true;

    @Test
    void failsFastWhenAModuleDependsOnOneThatIsNotInstalled() {
        List<EchnoModule> modules = List.of(module("reports", List.of("inspections")));

        assertThatThrownBy(() -> new ModuleRegistry(modules, ENTITLED_TO_EVERYTHING, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reports -> inspections");
    }

    @Test
    void failsFastOnADuplicateModuleId() {
        List<EchnoModule> modules = List.of(module("inspections", List.of()), module("inspections", List.of()));

        assertThatThrownBy(() -> new ModuleRegistry(modules, ENTITLED_TO_EVERYTHING, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inspections");
    }

    @Test
    void failsFastOnADependencyCycle() {
        List<EchnoModule> modules = List.of(
                module("a", List.of("b")), module("b", List.of("c")), module("c", List.of("a")));

        assertThatThrownBy(() -> new ModuleRegistry(modules, ENTITLED_TO_EVERYTHING, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void installedListsEveryManifestInIdOrderAndIsIndependentOfEntitlement() {
        ModuleRegistry registry = new ModuleRegistry(
                List.of(module("reports", List.of()), module("inspections", List.of())),
                (org, key) -> false, new MockEnvironment());

        assertThat(registry.installed()).extracting(ModuleManifest::id)
                .containsExactly("inspections", "reports");
        assertThat(registry.isInstalled("inspections")).isTrue();
        assertThat(registry.isInstalled("missing")).isFalse();
    }

    @Test
    void enabledForOrgReflectsTheResolver() {
        ModuleEntitlementResolver onlyOrgSeven = (org, key) -> org == 7L && "MODULE_INSPECTIONS".equals(key);
        ModuleRegistry registry = new ModuleRegistry(
                List.of(module("inspections", List.of())), onlyOrgSeven, new MockEnvironment());

        assertThat(registry.isEnabledForOrg("inspections", 7L)).isTrue();
        assertThat(registry.isEnabledForOrg("inspections", 8L)).isFalse();
        assertThat(registry.enabledForOrg(7L)).extracting(ModuleManifest::id).containsExactly("inspections");
        assertThat(registry.enabledForOrg(8L)).isEmpty();
    }

    @Test
    void killSwitchOverridesEntitlement() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("echno.modules.inspections.enabled", "false");
        ModuleRegistry registry = new ModuleRegistry(
                List.of(module("inspections", List.of())), ENTITLED_TO_EVERYTHING, environment);

        assertThat(registry.isSwitchedOn("inspections")).isFalse();
        assertThat(registry.isEntitled("inspections", 7L)).isTrue();
        assertThat(registry.isEnabledForOrg("inspections", 7L)).isFalse();
        assertThat(registry.enabledForOrg(7L)).isEmpty();
    }

    @Test
    void killSwitchDefaultsToOn() {
        ModuleRegistry registry = new ModuleRegistry(
                List.of(module("inspections", List.of())), ENTITLED_TO_EVERYTHING, new MockEnvironment());

        assertThat(registry.isSwitchedOn("inspections")).isTrue();
        assertThat(registry.isEnabledForOrg("inspections", 7L)).isTrue();
    }

    @Test
    void aModuleIsOnlyEnabledWhenItsDependenciesAre() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("echno.modules.inspections.enabled", "false");
        ModuleRegistry registry = new ModuleRegistry(
                List.of(module("inspections", List.of()), module("reports", List.of("inspections"))),
                ENTITLED_TO_EVERYTHING, environment);

        assertThat(registry.isSwitchedOn("reports")).isTrue();
        assertThat(registry.isEnabledForOrg("reports", 7L)).isFalse();
    }

    @Test
    void aFreeModuleFollowsItsEnabledByDefaultFlagAndNeverAsksTheResolver() {
        ModuleEntitlementResolver refusesEverything = (org, key) -> {
            throw new AssertionError("a free module must not consult the resolver");
        };
        ModuleRegistry registry = new ModuleRegistry(
                List.of(freeModule("chat", true), freeModule("labs", false)),
                refusesEverything, new MockEnvironment());

        assertThat(registry.isEnabledForOrg("chat", 7L)).isTrue();
        assertThat(registry.isEnabledForOrg("labs", 7L)).isFalse();
    }

    @Test
    void mergesHookContributionsWithTheManifest() {
        EchnoModule module = new EchnoModule() {
            @Override
            public ModuleManifest manifest() {
                return new ModuleManifest("inspections", "Inspections", "1.0.0", "MODULE_INSPECTIONS",
                        List.of(), List.of("inspections:view"),
                        List.of(new NavDescriptor("Inspections", "site", "/inspections", "clipboard",
                                List.of("inspections:view"))),
                        false);
            }

            @Override
            public void registerNavigation(NavRegistry nav) {
                nav.add("Defects", "site", "/inspections/defects", null, List.of("inspections:view"));
            }

            @Override
            public void registerPermissions(PermissionRegistry permissions) {
                permissions.add("inspections:manage").add("inspections:view");
            }
        };
        ModuleRegistry registry = new ModuleRegistry(List.of(module), ENTITLED_TO_EVERYTHING, new MockEnvironment());

        assertThat(registry.permissions("inspections")).containsExactly("inspections:view", "inspections:manage");
        assertThat(registry.navDescriptors("inspections")).extracting(NavDescriptor::path)
                .containsExactly("/inspections", "/inspections/defects");
    }

    @Test
    void unknownModulesAreNeverEnabled() {
        ModuleRegistry registry = new ModuleRegistry(List.of(), ENTITLED_TO_EVERYTHING, new MockEnvironment());

        assertThat(registry.installed()).isEmpty();
        assertThat(registry.isEnabledForOrg("missing", 7L)).isFalse();
        assertThat(registry.permissions("missing")).isEmpty();
        assertThat(Set.copyOf(registry.navDescriptors("missing"))).isEmpty();
    }

    private static EchnoModule module(String id, List<String> dependsOn) {
        return () -> new ModuleManifest(id, id, "1.0.0", "MODULE_" + id.toUpperCase(),
                dependsOn, List.of(), List.of(), false);
    }

    private static EchnoModule freeModule(String id, boolean enabledByDefault) {
        return () -> new ModuleManifest(id, id, "1.0.0", null, List.of(), List.of(), List.of(), enabledByDefault);
    }
}
