package org.tornotron.echno_backend.modules.workprogress;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.tornotron.echno_backend.common.module.ModuleManifest;
import org.tornotron.echno_backend.common.module.ModuleRegistry;

/** The module's contract with the registry: the manifest, the permission vocabulary and the guards. */
class WorkProgressModuleTest {

    private static final long ORG = 7L;

    private final WorkProgressModule module = new WorkProgressModule();

    @Test
    void manifestIsThePaywalledWorkProgressModule() {
        ModuleManifest manifest = module.manifest();

        assertThat(manifest.id()).isEqualTo("work-progress");
        assertThat(manifest.name()).isEqualTo("Work Progress");
        assertThat(manifest.entitlementFeatureKey()).isEqualTo("MODULE_WORK_PROGRESS");
        assertThat(manifest.isPaywalled()).isTrue();
        assertThat(manifest.dependsOn()).isEmpty();
        assertThat(manifest.navDescriptors()).isEmpty();
    }

    @Test
    void permissionsUseTheColonVocabulary() {
        assertThat(module.manifest().permissions())
                .containsExactly("work-progress:read", "work-progress:record")
                .allMatch(ModuleManifest.PERMISSION_KEY.asMatchPredicate());
    }

    @Test
    void theProjectTeamRecordsAndAnyMemberReads() {
        assertThat(WorkProgressModule.READ_GUARD).isEqualTo("@orgSecurity.isMemberOfCurrentTenant()");
        assertThat(WorkProgressModule.RECORD_GUARD)
                .startsWith("@orgSecurity.hasAnyOrgRoleForCurrentTenant(")
                .contains("'system-admin'", "'project-manager'", "'site-engineer'");
    }

    @Test
    void registersAndFollowsEntitlementAndTheKillSwitch() {
        ModuleRegistry entitled = new ModuleRegistry(List.of(module), (org, key) -> true, new MockEnvironment());
        ModuleRegistry dark = new ModuleRegistry(List.of(module), (org, key) -> false, new MockEnvironment());
        ModuleRegistry killed = new ModuleRegistry(List.of(module), (org, key) -> true,
                new MockEnvironment().withProperty("echno.modules.work-progress.enabled", "false"));

        assertThat(entitled.isEnabledForOrg("work-progress", ORG)).isTrue();
        assertThat(dark.isEnabledForOrg("work-progress", ORG)).isFalse();
        assertThat(killed.isEnabledForOrg("work-progress", ORG)).isFalse();
    }
}
