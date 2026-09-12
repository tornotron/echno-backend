package org.tornotron.echno_backend.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.Slice;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.module.EchnoModule;
import org.tornotron.echno_backend.common.module.GlobalReferenceData;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;

import java.util.List;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The boundary around the pluggable modules under {@code org.tornotron.echno_backend.modules}.
 *
 * <p>Scoped to that package only. The forty-odd legacy packages keep whatever coupling they have;
 * the point of these rules is that a module carved out from now on cannot grow the same coupling
 * back, in either direction. Three things are held:
 *
 * <ul>
 *   <li>Code outside a module reaches it only through its {@code api} subpackage, and one module
 *       reaches another the same way. Everything else in a module is its own business.
 *   <li>Every {@code @Entity} in a module is tenant scoped: it implements
 *       {@link TenantScopedEntity}, or it is an owned child reached through a {@code @ManyToOne}
 *       to one that does, or it says why not with {@link GlobalReferenceData}. A module that skips
 *       this is a cross-tenant leak, which is why it is a rule rather than a review note.
 *   <li>Every module package declares exactly one {@link EchnoModule} bean, and no such bean lives
 *       anywhere else. The registry is built from those beans; a module without one is invisible
 *       and one with two is ambiguous.
 * </ul>
 *
 * <p>The rules are built by the static factories so {@link ModuleBoundaryRuleTest} can run the
 * same rules over planted violations under a different root. The package is empty today, so the
 * rules whose subject is "classes in a module" are allowed an empty subject; the pairing test is
 * what proves they still bite.
 *
 * <p>Runs under {@code @AnalyzeClasses} so the imported class graph comes from ArchUnit's shared
 * cache, as every architecture test in this package does.
 */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    static final String ROOT = "org.tornotron.echno_backend";

    @ArchTest
    static final ArchRule coreReachesAModuleOnlyThroughItsApi = coreReachesModulesOnlyThroughApi(ROOT);

    @ArchTest
    static final ArchRule modulesReachEachOtherOnlyThroughApi = modulesReachEachOtherOnlyThroughApi(ROOT);

    @ArchTest
    static final ArchRule moduleEntitiesAreTenantScoped = moduleEntitiesAreTenantScoped(ROOT);

    @ArchTest
    static final ArchRule everyModuleDeclaresExactlyOneManifest = everyModuleDeclaresExactlyOneManifest(ROOT);

    @ArchTest
    static final ArchRule manifestsLiveInModules = manifestsLiveInModules(ROOT);

    // -------------------------------------------------------------------------------
    // Rule factories, parameterised by the root package so fixtures can be checked too
    // -------------------------------------------------------------------------------

    static String modules(String root) {
        return root + ".modules..";
    }

    static String moduleApi(String root) {
        return root + ".modules.*.api..";
    }

    /** Classes in a module that are not part of its published {@code api} subpackage. */
    static DescribedPredicate<JavaClass> moduleInternals(String root) {
        return resideInAPackage(modules(root)).and(not(resideInAPackage(moduleApi(root))))
                .as("module internals (under %s but outside %s)", modules(root), moduleApi(root));
    }

    static ArchRule coreReachesModulesOnlyThroughApi(String root) {
        return noClasses()
                .that().resideInAPackage(root + "..")
                .and().resideOutsideOfPackage(modules(root))
                .should().dependOnClassesThat(moduleInternals(root))
                .as("code outside a module may reach it only through its api subpackage")
                .allowEmptyShould(true);
    }

    static ArchRule modulesReachEachOtherOnlyThroughApi(String root) {
        return slices()
                .matching(root + ".modules.(*)..")
                .should().notDependOnEachOther()
                .ignoreDependency(alwaysTrue(), resideInAPackage(moduleApi(root)))
                .as("a module may reach another module only through its api subpackage")
                .allowEmptyShould(true);
    }

    static ArchRule moduleEntitiesAreTenantScoped(String root) {
        return classes()
                .that().resideInAPackage(modules(root))
                .and().areAnnotatedWith(Entity.class)
                .and().areNotAnnotatedWith(GlobalReferenceData.class)
                .should(beTenantScopedOrAnOwnedChild())
                .as("every module entity is tenant scoped, an owned child of a tenant-scoped entity, "
                        + "or marked @GlobalReferenceData")
                .allowEmptyShould(true);
    }

    static ArchRule everyModuleDeclaresExactlyOneManifest(String root) {
        return slices()
                .matching(root + ".modules.(*)..")
                .should(declareExactlyOneManifestBean())
                .as("every module package declares exactly one EchnoModule bean")
                .allowEmptyShould(true);
    }

    static ArchRule manifestsLiveInModules(String root) {
        return classes()
                .that().implement(EchnoModule.class)
                .should().resideInAPackage(modules(root))
                .as("an EchnoModule bean lives in a module package")
                .allowEmptyShould(true);
    }

    private static ArchCondition<JavaClass> beTenantScopedOrAnOwnedChild() {
        return new ArchCondition<>("implement TenantScopedEntity or hold a @ManyToOne to a class that does") {
            @Override
            public void check(JavaClass entity, ConditionEvents events) {
                boolean scoped = entity.isAssignableTo(TenantScopedEntity.class);
                boolean ownedChild = entity.getAllFields().stream().anyMatch(ModuleBoundaryTest::pointsAtATenantScopedParent);
                events.add(new SimpleConditionEvent(entity, scoped || ownedChild,
                        entity.getName() + " is a module @Entity that is neither tenant scoped nor an owned "
                                + "child of a tenant-scoped entity, and does not declare @GlobalReferenceData"));
            }
        };
    }

    private static boolean pointsAtATenantScopedParent(JavaField field) {
        return field.isAnnotatedWith(ManyToOne.class)
                && field.getRawType().isAssignableTo(TenantScopedEntity.class);
    }

    private static ArchCondition<Slice> declareExactlyOneManifestBean() {
        return new ArchCondition<>("declare exactly one EchnoModule bean") {
            @Override
            public void check(Slice module, ConditionEvents events) {
                List<String> manifests = module.stream()
                        .filter(ModuleBoundaryTest::isAManifestBean)
                        .map(JavaClass::getName)
                        .toList();
                events.add(new SimpleConditionEvent(module, manifests.size() == 1,
                        module.getDescription() + " declares " + manifests.size()
                                + " EchnoModule bean(s), expected exactly one: " + manifests));
            }
        };
    }

    private static boolean isAManifestBean(JavaClass javaClass) {
        return javaClass.isAssignableTo(EchnoModule.class)
                && !javaClass.isInterface()
                && (javaClass.isAnnotatedWith(Component.class) || javaClass.isMetaAnnotatedWith(Component.class));
    }
}
