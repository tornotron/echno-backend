package org.tornotron.echno_backend.modules.documentingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.tornotron.echno_backend.common.module.ModuleManifest;
import org.tornotron.echno_backend.common.module.ModuleRegistry;

/**
 * The manifest and the switch. Each case uses an environment with no {@code application.yml}
 * behind it, so "off" here is the default in code and not the one in the YAML line.
 */
class DocumentIngestionModuleTest {

    private static final long ORG = 7L;
    private static final String SWITCH = "echno.modules.document-ingestion.enabled";
    private static final String ALLOW = "echno.modules.document-ingestion.allowed-org-ids";

    @Test
    void manifestIsTheGatedModuleOfTheDesign() {
        ModuleManifest manifest = new DocumentIngestionModule(new MockEnvironment()).manifest();

        assertThat(manifest.id()).isEqualTo("document-ingestion");
        assertThat(manifest.version()).isEqualTo("0.1.0");
        assertThat(manifest.entitlementFeatureKey()).isEqualTo("MODULE_DOCUMENT_INGESTION");
        assertThat(manifest.enabledByDefault()).isFalse();
        assertThat(manifest.dependsOn()).isEmpty();
    }

    @Test
    void permissionsUseTheColonVocabulary() {
        assertThat(new DocumentIngestionModule(new MockEnvironment()).manifest().permissions())
                .containsExactly("document-ingestion:read", "document-ingestion:upload", "document-ingestion:review",
                        "document-ingestion:apply", "document-ingestion:admin")
                .allMatch(ModuleManifest.PERMISSION_KEY.asMatchPredicate());
    }

    @Test
    void guardsMatchTheTableInTheDesign() {
        assertThat(DocumentIngestionModule.READ_GUARD).isEqualTo("@orgSecurity.isMemberOfCurrentTenant()");
        assertThat(DocumentIngestionModule.UPLOAD_GUARD)
                .contains("'system-admin'", "'project-manager'", "'qa-engineer'", "'site-engineer'");
        assertThat(DocumentIngestionModule.APPLY_GUARD)
                .contains("'system-admin'", "'project-manager'").doesNotContain("engineer");
        assertThat(DocumentIngestionModule.ADMIN_GUARD)
                .contains("'system-admin'").doesNotContain("project-manager");
    }

    @Test
    void withNoConfigurationTheModuleIsOffEvenWhenEveryoneIsEntitled() {
        MockEnvironment nothing = new MockEnvironment();
        ModuleRegistry registry = registry(nothing);

        assertThat(new DocumentIngestionSwitch(nothing).isOn()).isFalse();
        assertThat(registry.isSwitchedOn("document-ingestion")).isFalse();
        assertThat(registry.isEnabledForOrg("document-ingestion", ORG)).isFalse();
    }

    @Test
    void theSwitchOnAloneOpensItToNobody() {
        ModuleRegistry registry = registry(new MockEnvironment().withProperty(SWITCH, "true"));

        assertThat(registry.isSwitchedOn("document-ingestion")).isTrue();
        assertThat(registry.isEnabledForOrg("document-ingestion", ORG)).isFalse();
    }

    @Test
    void theAllowListOpensItToTheNamedOrganizationsOnly() {
        ModuleRegistry registry = registry(new MockEnvironment()
                .withProperty(SWITCH, "true").withProperty(ALLOW, " 7, 9 "));

        assertThat(registry.isEnabledForOrg("document-ingestion", 7L)).isTrue();
        assertThat(registry.isEnabledForOrg("document-ingestion", 9L)).isTrue();
        assertThat(registry.isEnabledForOrg("document-ingestion", 8L)).isFalse();
    }

    @Test
    void anAllowListWithNoSwitchOpensNothing() {
        ModuleRegistry registry = registry(new MockEnvironment().withProperty(ALLOW, "7"));

        assertThat(registry.isEnabledForOrg("document-ingestion", ORG)).isFalse();
    }

    @Test
    void aBadAllowListEntryIsIgnoredAndDoesNotStopTheBoot() {
        DocumentIngestionSwitch operatorSwitch = new DocumentIngestionSwitch(
                new MockEnvironment().withProperty(SWITCH, "true").withProperty(ALLOW, "seven,9"));

        assertThat(operatorSwitch.allows(9L)).isTrue();
        assertThat(operatorSwitch.allows(7L)).isFalse();
        assertThat(operatorSwitch.allows(null)).isFalse();
    }

    @Test
    void aValueThatIsNotTrueReadsAsOff() {
        assertThat(new DocumentIngestionSwitch(new MockEnvironment().withProperty(SWITCH, "yes")).isOn()).isFalse();
        assertThat(new DocumentIngestionSwitch(new MockEnvironment().withProperty(SWITCH, "TRUE")).isOn()).isTrue();
    }

    private static ModuleRegistry registry(MockEnvironment environment) {
        return new ModuleRegistry(List.of(new DocumentIngestionModule(environment)), (org, key) -> true, environment);
    }
}
