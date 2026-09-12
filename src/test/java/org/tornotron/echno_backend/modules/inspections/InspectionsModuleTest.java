package org.tornotron.echno_backend.modules.inspections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mock.env.MockEnvironment;
import org.tornotron.echno_backend.aspect.SubscriptionAspect;
import org.tornotron.echno_backend.billing.dto.FeatureAccessResultDto;
import org.tornotron.echno_backend.billing.entitlement.EntitlementPolicy;
import org.tornotron.echno_backend.common.exception.SubscriptionAccessDeniedException;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.common.module.ModuleEntitlementResolver;
import org.tornotron.echno_backend.common.module.ModuleManifest;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.common.module.NavDescriptor;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.modules.inspections.compliance.job.ComplianceGenerationJobDispatcher;
import org.tornotron.echno_backend.modules.inspections.compliance.sweep.ComplianceRuleSweep;
import org.tornotron.echno_backend.modules.inspections.compliance.web.ComplianceControllerWeb;
import org.tornotron.echno_backend.modules.inspections.pdf.InspectionReportPdfService;
import org.tornotron.echno_backend.modules.inspections.service.DefectAnnotationService;
import org.tornotron.echno_backend.modules.inspections.service.InspectionEvidenceService;
import org.tornotron.echno_backend.modules.inspections.service.InspectionService;
import org.tornotron.echno_backend.modules.inspections.web.ChecklistTemplateControllerWeb;
import org.tornotron.echno_backend.modules.inspections.web.InspectionControllerWeb;
import org.tornotron.echno_backend.modules.inspections.web.NcrControllerWeb;

/**
 * The module's contract with the registry and the gate: what the manifest says, that every
 * web controller is behind the feature key, what a dark organization gets on an inspection
 * endpoint in each entitlement mode, and that the kill switch reaches the scheduled jobs.
 */
class InspectionsModuleTest {

    private static final Long DARK_ORG = 31L;
    private static final Long LIT_ORG = 32L;

    private final InspectionsModule module = new InspectionsModule();
    private final ListAppender<ILoggingEvent> policyLog = new ListAppender<>();

    @BeforeEach
    void logging() {
        policyLog.start();
        ((Logger) LoggerFactory.getLogger(EntitlementPolicy.class)).addAppender(policyLog);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        ((Logger) LoggerFactory.getLogger(EntitlementPolicy.class)).detachAppender(policyLog);
    }

    // -------------------------------------------------------------------------------
    // Manifest
    // -------------------------------------------------------------------------------

    @Test
    void manifestIsThePaywalledInspectionsModule() {
        ModuleManifest manifest = module.manifest();

        assertThat(manifest.id()).isEqualTo("inspections");
        assertThat(manifest.name()).isEqualTo("Site Inspections");
        assertThat(manifest.version()).isEqualTo("1.0.0");
        assertThat(manifest.entitlementFeatureKey()).isEqualTo("MODULE_INSPECTIONS");
        assertThat(manifest.isPaywalled()).isTrue();
        assertThat(manifest.enabledByDefault()).isFalse();
        assertThat(manifest.dependsOn()).isEmpty();
        assertThat(manifest.permissions()).isNotEmpty().allSatisfy(key -> assertThat(key).startsWith("inspections."));
        assertThat(manifest).isSameAs(module.manifest());
    }

    @Test
    void navDescriptorsMatchTheWebRoutes() {
        List<NavDescriptor> nav = module.manifest().navDescriptors();

        assertThat(nav).extracting(NavDescriptor::path).containsExactly(
                "/users/dashboard/inspections",
                "/users/dashboard/inspections/qa-qc",
                "/users/dashboard/inspections/safety",
                "/users/dashboard/inspections/ncr",
                "/users/dashboard/inspections/checklists",
                "/users/dashboard/inspections/reports",
                "/users/dashboard/projects/all-projects/{projectId}/compliance");
        assertThat(nav).extracting(NavDescriptor::label).containsExactly(
                "Inspections", "QA/QC", "Safety", "NCR / Defects", "Checklist Builder", "Reports", "Compliance");
        assertThat(nav).filteredOn(d -> d.path().startsWith("/users/dashboard/inspections"))
                .extracting(NavDescriptor::section).containsOnly("inspections");
    }

    @Test
    void registryAnswersFromTheResolverAndTheKillSwitch() {
        ModuleEntitlementResolver resolver = (orgId, feature) ->
                "MODULE_INSPECTIONS".equals(feature) && LIT_ORG.equals(orgId);
        ModuleRegistry registry = new ModuleRegistry(List.of(module), resolver, new MockEnvironment());

        assertThat(registry.isEnabledForOrg("inspections", LIT_ORG)).isTrue();
        assertThat(registry.isEnabledForOrg("inspections", DARK_ORG)).isFalse();

        MockEnvironment off = new MockEnvironment().withProperty(InspectionsModuleEnabled.PROPERTY, "false");
        assertThat(new ModuleRegistry(List.of(module), resolver, off).isEnabledForOrg("inspections", LIT_ORG))
                .as("the operator kill switch beats entitlement")
                .isFalse();
    }

    // -------------------------------------------------------------------------------
    // The gate on the web controllers
    // -------------------------------------------------------------------------------

    @Test
    void everyWebControllerIsGatedOnTheModuleFeatureAtClassLevel() {
        for (Class<?> controller : List.of(InspectionControllerWeb.class, ChecklistTemplateControllerWeb.class,
                NcrControllerWeb.class, ComplianceControllerWeb.class)) {
            RequireSubscription gate = controller.getAnnotation(RequireSubscription.class);
            assertThat(gate).as("%s carries the module gate", controller.getSimpleName()).isNotNull();
            assertThat(gate.feature()).isEqualTo(InspectionsModule.FEATURE_KEY);
        }
    }

    @Test
    void darkOrganizationGetsTheAdvisoryWarningAndTheAnswerInAdvisoryMode() {
        InspectionService service = mock(InspectionService.class);
        UUID id = UUID.randomUUID();
        InspectionControllerWeb controller = gated(service, "advisory", FeatureAccessResultDto.featureNotInPlan());
        TenantContext.setCurrentOrgId(DARK_ORG);

        controller.get(id);

        verify(service).findById(id);

        assertThat(policyLog.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("organization " + DARK_ORG)
                    .contains(InspectionsModule.FEATURE_KEY);
        });
    }

    @Test
    void darkOrganizationIsRefusedInEnforceModeBeforeTheServiceIsCalled() {
        InspectionService service = mock(InspectionService.class);
        InspectionControllerWeb controller = gated(service, "enforce", FeatureAccessResultDto.featureNotInPlan());
        TenantContext.setCurrentOrgId(DARK_ORG);

        assertThatExceptionOfType(SubscriptionAccessDeniedException.class)
                .isThrownBy(() -> controller.get(UUID.randomUUID()))
                .satisfies(ex -> assertThat(ex.getFeatureCode()).isEqualTo(InspectionsModule.FEATURE_KEY));
        verifyNoInteractions(service);
        assertThat(policyLog.list).isEmpty();
    }

    @Test
    void entitledOrganizationPassesInEnforceMode() {
        InspectionService service = mock(InspectionService.class);
        UUID id = UUID.randomUUID();
        InspectionControllerWeb controller = gated(service, "enforce", FeatureAccessResultDto.allowed());
        TenantContext.setCurrentOrgId(LIT_ORG);

        controller.get(id);

        verify(service).findById(id);
        assertThat(policyLog.list).isEmpty();
    }

    /**
     * The real controller behind the real aspect, with the subscription lookup scripted. This
     * is the same proxy the container builds; only the container is missing.
     */
    private InspectionControllerWeb gated(InspectionService service, String mode, FeatureAccessResultDto access) {
        SubscriptionService subscriptionService = mock(SubscriptionService.class);
        when(subscriptionService.checkFeatureAccess(LIT_ORG, InspectionsModule.FEATURE_KEY)).thenReturn(access);
        when(subscriptionService.checkFeatureAccess(DARK_ORG, InspectionsModule.FEATURE_KEY)).thenReturn(access);
        InspectionControllerWeb target = new InspectionControllerWeb(service, mock(DefectAnnotationService.class),
                mock(InspectionEvidenceService.class), mock(InspectionReportPdfService.class));
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new SubscriptionAspect(subscriptionService, mock(UserContextService.class),
                new EntitlementPolicy(mode)));
        return factory.getProxy();
    }

    // -------------------------------------------------------------------------------
    // The kill switch reaches the jobs
    // -------------------------------------------------------------------------------

    @Test
    void theJobsCarryTheKillSwitchOnTopOfTheirOwnSwitches() {
        for (Class<?> job : List.of(ComplianceRuleSweep.class, ComplianceGenerationJobDispatcher.class)) {
            assertThat(job.getAnnotation(InspectionsModuleEnabled.class))
                    .as("%s is switched off with the module", job.getSimpleName()).isNotNull();
            assertThat(job.getAnnotation(ConditionalOnProperty.class))
                    .as("%s keeps its own compliance.* switch", job.getSimpleName()).isNotNull();
        }
        ConditionalOnProperty killSwitch = InspectionsModuleEnabled.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(killSwitch.name()).containsExactly("echno.modules.inspections.enabled");
        assertThat(killSwitch.havingValue()).isEqualTo("true");
        assertThat(killSwitch.matchIfMissing()).as("absent means on, as the registry reads it").isTrue();
    }
}
