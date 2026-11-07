package org.tornotron.echno_backend.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Provider types stay in the provider's adapter. The port in {@code common.identity} names none of
 * them, so swapping an adapter touches nothing else.
 */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class IdentityProviderBoundaryTest {

    private static final String PORT = "org.tornotron.echno_backend.common.identity";
    private static final String KEYCLOAK_TYPES = "org.keycloak..";
    private static final String WORKOS_TYPES = "com.workos..";

    @ArchTest
    static final ArchRule thePortNamesNoProviderType = noClasses()
            .that().resideInAPackage(PORT)
            .should().dependOnClassesThat().resideInAnyPackage(KEYCLOAK_TYPES, WORKOS_TYPES)
            .because("a provider-specific type on the port would tie every caller to that provider");

    @ArchTest
    static final ArchRule workosTypesStayInItsAdapter = noClasses()
            .that().resideOutsideOfPackage(PORT + ".workos..")
            .should().dependOnClassesThat().resideInAPackage(WORKOS_TYPES)
            .allowEmptyShould(true);
}
