package org.tornotron.echno_backend.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * Ratchet for the endpoint-authorization sweep: every request-mapped method in a
 * {@code @RestController} must carry a {@code @PreAuthorize} (on the method or the
 * class), so a new endpoint cannot ship without an explicit authorization decision.
 *
 * <p>{@link #GRANDFATHERED} lists controllers that predate this rule and are still
 * unguarded. They are being migrated batch by batch; as a controller is fully
 * guarded it is removed from this set. The rule keeps the gap from growing and
 * forces every new controller to be guarded. A deliberately public endpoint must
 * use {@code @PreAuthorize("permitAll()")} rather than be left un-annotated.
 *
 * <p>Runs under {@code @AnalyzeClasses} so the imported class graph comes from ArchUnit's own
 * cache, which every architecture test in this package shares and which holds it behind a soft
 * reference. See {@link UnboundedRepositoryReadTest} for why that matters in a 1 GB test JVM.
 */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class EndpointAuthorizationTest {

    /**
     * Controllers exempt from the guard rule. The endpoint-authorization sweep is
     * complete, so this is empty: every controller endpoint must be guarded. Do NOT
     * add to this set - guard the endpoint instead (use {@code permitAll()} for a
     * deliberately public one).
     */
    private static final Set<String> GRANDFATHERED = Set.of();

    private static final DescribedPredicate<JavaClass> NOT_GRANDFATHERED =
            new DescribedPredicate<>("not a grandfathered controller") {
                @Override
                public boolean test(JavaClass javaClass) {
                    return !GRANDFATHERED.contains(javaClass.getSimpleName());
                }
            };

    @ArchTest
    static final ArchRule everyControllerEndpointHasAnAuthorizationGuard = methods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .and().areDeclaredInClassesThat(NOT_GRANDFATHERED)
            .and().areMetaAnnotatedWith(RequestMapping.class)
            .should().beAnnotatedWith(PreAuthorize.class)
            .orShould().beDeclaredInClassesThat().areAnnotatedWith(PreAuthorize.class);
}
