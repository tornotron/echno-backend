package org.tornotron.echno_backend.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
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
    private static final String KEYCLOAK_ADAPTER = PORT + ".keycloak..";

    /**
     * The Keycloak-only machinery that still talks to Keycloak's admin API directly. All of it is
     * absent unless Keycloak is the provider. The realm bootstrap goes when the stagings leave
     * Keycloak, and the management endpoints with it. Do not add to this list: a new call to
     * Keycloak belongs in the adapter.
     */
    private static final DescribedPredicate<JavaClass> KEYCLOAK_ONLY_MACHINERY =
            DescribedPredicate.describe("the realm bootstrap or the Keycloak management endpoints",
                    javaClass -> {
                        String name = javaClass.getName();
                        return name.equals("org.tornotron.echno_backend.common.configuration.KeycloakConfig")
                                || name.startsWith("org.tornotron.echno_backend.common.configuration.KeycloakInitializer")
                                || name.startsWith("org.tornotron.echno_backend.keycloak.KeycloakManagementService");
                    });

    @ArchTest
    static final ArchRule thePortNamesNoProviderType = noClasses()
            .that().resideInAPackage(PORT)
            .should().dependOnClassesThat().resideInAnyPackage(KEYCLOAK_TYPES, WORKOS_TYPES)
            .because("a provider-specific type on the port would tie every caller to that provider");

    @ArchTest
    static final ArchRule keycloakTypesStayInItsAdapter = noClasses()
            .that(resideOutsideOfPackage(KEYCLOAK_ADAPTER).and(DescribedPredicate.not(KEYCLOAK_ONLY_MACHINERY)))
            .should().dependOnClassesThat().resideInAnyPackage(KEYCLOAK_TYPES, "jakarta.ws.rs..")
            .because("everything else reaches Keycloak through the IdentityProvider port");

    @ArchTest
    static final ArchRule theKeycloakAdapterIsReachedOnlyThroughThePort = noClasses()
            .that().resideOutsideOfPackage(KEYCLOAK_ADAPTER)
            .should().dependOnClassesThat().resideInAPackage(KEYCLOAK_ADAPTER)
            .because("a caller that names the adapter would break when the provider changes");

    @ArchTest
    static final ArchRule workosTypesStayInItsAdapter = noClasses()
            .that().resideOutsideOfPackage(PORT + ".workos..")
            .should().dependOnClassesThat().resideInAPackage(WORKOS_TYPES)
            .allowEmptyShould(true);
}
