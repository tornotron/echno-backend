package org.tornotron.echno_backend.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.EchnoBackendApplication;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the module rules, not for the modules.
 *
 * <p>{@link ModuleBoundaryTest} runs against a {@code modules} package that is empty today, so
 * every one of its rules passes, which is also what a rule that matches nothing does. Each rule
 * is therefore driven here over a planted tree that obeys it and one that breaks it, under a
 * root of its own, so that a rule which stops biting turns something red.
 *
 * <p>The fixture trees live outside the application package; see their {@code package-info}
 * for why, and {@link #fixturesAreOutsideTheApplicationScanRoot()} for the assertion that keeps
 * them there.
 */
class ModuleBoundaryRuleTest {

    private static final String CLEAN = "org.tornotron.echno_modulefixtures.clean";
    private static final String VIOLATING = "org.tornotron.echno_modulefixtures.violating";

    /**
     * Instance fields rather than statics, so the imported graphs are collectable once the test
     * that used them is done, as {@link PublicEndpointTenantExposureRuleTest} does.
     */
    private final JavaClasses clean = new ClassFileImporter().importPackages(CLEAN);
    private final JavaClasses violating = new ClassFileImporter().importPackages(VIOLATING);

    @Test
    void fixturesAreOutsideTheApplicationScanRoot() {
        String scanRoot = EchnoBackendApplication.class.getPackageName();
        assertThat(CLEAN).doesNotStartWith(scanRoot + ".").isNotEqualTo(scanRoot);
        assertThat(VIOLATING).doesNotStartWith(scanRoot + ".").isNotEqualTo(scanRoot);
        assertThat(clean.size()).as("the clean tree imported").isGreaterThan(0);
        assertThat(violating.size()).as("the violating tree imported").isGreaterThan(0);
    }

    @Test
    void coreReachesModulesOnlyThroughApi() {
        assertPasses(ModuleBoundaryTest::coreReachesModulesOnlyThroughApi);
        assertFails(ModuleBoundaryTest::coreReachesModulesOnlyThroughApi, "CoreReachesAlphaInternals");
    }

    @Test
    void modulesReachEachOtherOnlyThroughApi() {
        assertPasses(ModuleBoundaryTest::modulesReachEachOtherOnlyThroughApi);
        assertFails(ModuleBoundaryTest::modulesReachEachOtherOnlyThroughApi, "BetaReachesAlphaInternals");
    }

    @Test
    void moduleEntitiesAreTenantScoped() {
        assertPasses(ModuleBoundaryTest::moduleEntitiesAreTenantScoped);
        EvaluationResult result = assertFails(ModuleBoundaryTest::moduleEntitiesAreTenantScoped, "LeakyEntity");
        assertThat(result.getFailureReport().toString())
                .as("an optional association is not ownership: the child could be saved with no parent")
                .contains("LooselyOwnedChild")
                .as("only the offenders are reported; the owned child and the marked catalogue pass")
                .doesNotContain("AlphaRecordItem")
                .doesNotContain("AlphaCatalogue");
    }

    @Test
    void everyModuleDeclaresExactlyOneManifest() {
        assertPasses(ModuleBoundaryTest::everyModuleDeclaresExactlyOneManifest);
        EvaluationResult result = assertFails(ModuleBoundaryTest::everyModuleDeclaresExactlyOneManifest, "alpha");
        assertThat(result.getFailureReport().toString())
                .as("alpha has two manifests and beta has none; both are reported")
                .contains("declares 2 EchnoModule bean(s)")
                .contains("declares 0 EchnoModule bean(s)");
    }

    @Test
    void manifestsLiveInModules() {
        assertPasses(ModuleBoundaryTest::manifestsLiveInModules);
        assertFails(ModuleBoundaryTest::manifestsLiveInModules, "StrayModule");
    }

    private void assertPasses(Function<String, ArchRule> rule) {
        EvaluationResult result = rule.apply(CLEAN).evaluate(clean);
        assertThat(result.hasViolation())
                .as("the clean tree must satisfy the rule: %s", result.getFailureReport())
                .isFalse();
    }

    private EvaluationResult assertFails(Function<String, ArchRule> rule, String offender) {
        EvaluationResult result = rule.apply(VIOLATING).evaluate(violating);
        assertThat(result.hasViolation()).as("the violating tree must fail the rule").isTrue();
        assertThat(result.getFailureReport().toString()).contains(offender);
        return result;
    }
}
