package org.tornotron.echno_backend.modules.inspections.events;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.hibernate.annotations.Immutable;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.CrudRepository;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionEvent;
import org.tornotron.echno_backend.modules.inspections.repositories.InspectionEventQueries;
import org.tornotron.echno_backend.modules.inspections.repositories.InspectionEventRepository;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * The append-only guarantee of the event log, held as a rule rather than a review note: the
 * repository exposes no delete and no modifying query, and the entity cannot be changed after
 * construction. A bulk JPQL update is refused at runtime by Hibernate's immutable-entity
 * setting, which {@code InspectionEventRecorderIT} proves against the database.
 */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend.modules.inspections",
        importOptions = ImportOption.DoNotIncludeTests.class)
class InspectionEventAppendOnlyTest {

    @ArchTest
    static final ArchRule repositoryDoesNotInheritADelete = noClasses()
            .that().haveSimpleNameStartingWith("InspectionEvent")
            .and().resideInAPackage("..inspections.repositories..")
            .should().beAssignableTo(CrudRepository.class)
            .orShould().beAssignableTo(JpaSpecificationExecutor.class)
            .because("both bring a delete with them, and the event log is append-only");

    @ArchTest
    static final ArchRule repositoryDeclaresNoDeleteOrUpdate = noMethods()
            .that().areDeclaredInClassesThat().haveSimpleNameStartingWith("InspectionEvent")
            .and().areDeclaredInClassesThat().resideInAPackage("..inspections.repositories..")
            .should().haveNameMatching("(delete|remove|update|modify|purge|truncate).*")
            .orShould().beAnnotatedWith(Modifying.class)
            .because("an event is written once and read thereafter");

    @ArchTest
    static final ArchRule repositoryIsTheExpectedShape = classes()
            .that().areAssignableTo(InspectionEventRepository.class)
            .or().areAssignableTo(InspectionEventQueries.class)
            .should().resideInAPackage("..inspections.repositories..");

    @ArchTest
    static final ArchRule entityIsImmutable = classes()
            .that().areAssignableTo(InspectionEvent.class)
            .should().beAnnotatedWith(Immutable.class);

    @ArchTest
    static final ArchRule entityHasNoSetterBeyondTheTenantContract = noMethods()
            .that().areDeclaredIn(InspectionEvent.class)
            .and().haveNameStartingWith("set")
            .and().doNotHaveName("setOrganization")
            .should().bePublic()
            .orShould().beProtected()
            .orShould().bePackagePrivate()
            .because("every field is fixed at construction; setOrganization exists only for TenantScopedEntity")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule nothingOutsideTheRecorderSavesAnEvent = noClasses()
            .that().resideInAPackage("org.tornotron.echno_backend..")
            .and().doNotHaveSimpleName("InspectionEventRecorder")
            .should().callMethod(InspectionEventRepository.class, "save", InspectionEvent.class)
            .because("the recorder is the one way into the log, so every event carries its actor and transaction");
}
