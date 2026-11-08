package org.tornotron.echno_backend.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * The guarantee of section 6.5 of the design, in code. Extraction, parsing and job code may not
 * reach a core service, a repository outside the module, or another module's {@code api} port, apart
 * from a short named list of read-only lookups; so the only way a value reaches a live table is the
 * {@code apply} package, and every bean there asks for a signed-in caller.
 *
 * <p>The rules run over the application and over planted fixtures under
 * {@code org.tornotron.echno_ingestfixtures}, so a rule that stops biting turns something red.
 */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class DocumentIngestionWriteBoundaryTest {

    static final String ROOT = "org.tornotron.echno_backend";

    /** Read-only lookups extraction may use. Adding one is a review decision, not a convenience. */
    static final Set<String> READ_ONLY_LOOKUPS = Set.of("FileStorageService");

    @ArchTest
    static final ArchRule extractionReachesNoLiveWriter = extractionReachesNoLiveWriter(ROOT);

    @ArchTest
    static final ArchRule everyApplyBeanNeedsASignedInCaller = everyApplyBeanNeedsASignedInCaller(ROOT);

    static String module(String root) {
        return root + ".modules.documentingestion";
    }

    static DescribedPredicate<JavaClass> liveWriters(String root) {
        return new DescribedPredicate<>("a core service, a repository outside the module, or a module api port") {
            @Override
            public boolean test(JavaClass target) {
                String pkg = target.getPackageName();
                if (!pkg.startsWith(root + ".") || pkg.startsWith(module(root))) {
                    return false;
                }
                if (READ_ONLY_LOOKUPS.contains(target.getSimpleName())) {
                    return false;
                }
                return target.getSimpleName().endsWith("Service") || target.getSimpleName().endsWith("Repository")
                        || pkg.matches(java.util.regex.Pattern.quote(root) + "\\.modules\\.[^.]+\\.api(\\..*)?");
            }
        };
    }

    static ArchRule extractionReachesNoLiveWriter(String root) {
        return noClasses()
                .that().resideInAnyPackage(module(root) + ".extraction..", module(root) + ".parsing..",
                        module(root) + ".job..")
                .should().dependOnClassesThat(liveWriters(root))
                .as("extraction, parsing and job code writes candidates only")
                .allowEmptyShould(true);
    }

    static ArchRule everyApplyBeanNeedsASignedInCaller(String root) {
        return classes()
                .that().resideInAPackage(module(root) + ".apply..")
                .and(new DescribedPredicate<JavaClass>("are Spring beans") {
                    @Override
                    public boolean test(JavaClass c) {
                        return c.isAnnotatedWith(Service.class) || c.isAnnotatedWith(Component.class);
                    }
                })
                .should().beAnnotatedWith(PreAuthorize.class)
                .as("every bean in the apply package requires an authenticated caller")
                .allowEmptyShould(true);
    }

    @Test
    void theRulesBiteOnPlantedViolationsAndPassACleanTree() {
        String clean = "org.tornotron.echno_ingestfixtures.clean";
        String violating = "org.tornotron.echno_ingestfixtures.violating";
        JavaClasses cleanClasses = new ClassFileImporter().importPackages(clean);
        JavaClasses violatingClasses = new ClassFileImporter().importPackages(violating);
        assertThat(cleanClasses.size()).isGreaterThan(0);

        assertThat(extractionReachesNoLiveWriter(clean).evaluate(cleanClasses).hasViolation()).isFalse();
        assertThat(everyApplyBeanNeedsASignedInCaller(clean).evaluate(cleanClasses).hasViolation()).isFalse();

        EvaluationResult reach = extractionReachesNoLiveWriter(violating).evaluate(violatingClasses);
        assertThat(reach.getFailureReport().toString())
                .contains("ExtractorThatWritesAProject", "JobThatCallsAnApiPort", "ChunkReaderThatWritesAProject")
                .doesNotContain("ExtractorThatReadsAFile");
        assertThat(everyApplyBeanNeedsASignedInCaller(violating).evaluate(violatingClasses).getFailureReport().toString())
                .contains("UnguardedApplier");
    }
}
