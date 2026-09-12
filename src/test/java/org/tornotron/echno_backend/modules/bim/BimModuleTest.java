package org.tornotron.echno_backend.modules.bim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.aspect.SubscriptionAspect;
import org.tornotron.echno_backend.billing.dto.FeatureAccessResultDto;
import org.tornotron.echno_backend.common.exception.SubscriptionAccessDeniedException;
import org.tornotron.echno_backend.billing.entitlement.EntitlementPolicy;
import org.tornotron.echno_backend.common.module.ModuleEntitlementResolver;
import org.tornotron.echno_backend.common.module.ModuleManifest;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.bim.service.BimElementService;
import org.tornotron.echno_backend.modules.bim.service.BimModelService;
import org.tornotron.echno_backend.modules.bim.web.BimModelController;
import org.tornotron.echno_backend.modules.inspections.InspectionsModule;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The module's contract with the registry and the gate: the manifest, that the controller is
 * behind the feature key, that a dark organization is refused in enforce mode before the
 * service is called, and that the kill switch is wired.
 */
class BimModuleTest {

    private static final Long DARK_ORG = 41L;
    private static final Long LIT_ORG = 42L;

    private final BimModule module = new BimModule();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void manifestIsThePaywalledBimModuleDependingOnInspections() {
        ModuleManifest manifest = module.manifest();

        assertThat(manifest.id()).isEqualTo("bim");
        assertThat(manifest.entitlementFeatureKey()).isEqualTo("MODULE_BIM");
        assertThat(manifest.isPaywalled()).isTrue();
        assertThat(manifest.enabledByDefault()).isFalse();
        assertThat(manifest.dependsOn()).containsExactly(InspectionsModule.ID);
        assertThat(manifest.permissions()).containsExactly("bim.view", "bim.manage");
        assertThat(manifest.navDescriptors()).singleElement()
                .satisfies(nav -> assertThat(nav.path()).endsWith("/{projectId}/bim"));
        assertThat(manifest).isSameAs(module.manifest());
    }

    @Test
    void registryAnswersFromTheResolverAndTheKillSwitch() {
        ModuleEntitlementResolver resolver = (orgId, feature) -> LIT_ORG.equals(orgId);
        List<org.tornotron.echno_backend.common.module.EchnoModule> installed =
                List.of(module, new InspectionsModule());
        ModuleRegistry registry = new ModuleRegistry(installed, resolver, new MockEnvironment());

        assertThat(registry.isEnabledForOrg("bim", LIT_ORG)).isTrue();
        assertThat(registry.isEnabledForOrg("bim", DARK_ORG)).isFalse();

        MockEnvironment off = new MockEnvironment().withProperty(BimModuleEnabled.PROPERTY, "false");
        assertThat(new ModuleRegistry(installed, resolver, off).isEnabledForOrg("bim", LIT_ORG))
                .as("the operator kill switch beats entitlement")
                .isFalse();

        ConditionalOnProperty killSwitch = BimModuleEnabled.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(killSwitch.name()).containsExactly("echno.modules.bim.enabled");
        assertThat(killSwitch.matchIfMissing()).isTrue();
    }

    @Test
    void theControllerIsGatedAtClassLevelAndEveryHandlerCarriesPreAuthorize() {
        RequireSubscription gate = BimModelController.class.getAnnotation(RequireSubscription.class);
        assertThat(gate).isNotNull();
        assertThat(gate.feature()).isEqualTo(BimModule.FEATURE_KEY);
        assertThat(BimModelController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/bim");

        for (Method m : BimModelController.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers()) || m.isSynthetic()) {
                continue;
            }
            assertThat(m.getAnnotation(PreAuthorize.class))
                    .as("%s carries @PreAuthorize", m.getName()).isNotNull();
        }
    }

    @Test
    void darkOrganizationIsRefusedInEnforceModeBeforeTheServiceIsCalled() {
        BimModelService service = mock(BimModelService.class);
        BimModelController controller = gated(service, "enforce", FeatureAccessResultDto.featureNotInPlan());
        TenantContext.setCurrentOrgId(DARK_ORG);

        assertThatExceptionOfType(SubscriptionAccessDeniedException.class)
                .isThrownBy(() -> controller.get(UUID.randomUUID()))
                .satisfies(ex -> assertThat(ex.getFeatureCode()).isEqualTo(BimModule.FEATURE_KEY));
        verifyNoInteractions(service);
    }

    @Test
    void entitledOrganizationPassesInEnforceMode() {
        BimModelService service = mock(BimModelService.class);
        BimModelController controller = gated(service, "enforce", FeatureAccessResultDto.allowed());
        TenantContext.setCurrentOrgId(LIT_ORG);
        UUID id = UUID.randomUUID();

        controller.get(id);

        verify(service).get(id);
    }

    private BimModelController gated(BimModelService service, String mode, FeatureAccessResultDto access) {
        SubscriptionService subscriptionService = mock(SubscriptionService.class);
        when(subscriptionService.checkFeatureAccess(LIT_ORG, BimModule.FEATURE_KEY)).thenReturn(access);
        when(subscriptionService.checkFeatureAccess(DARK_ORG, BimModule.FEATURE_KEY)).thenReturn(access);
        AspectJProxyFactory factory = new AspectJProxyFactory(new BimModelController(service, mock(BimElementService.class)));
        factory.setProxyTargetClass(true);
        factory.addAspect(new SubscriptionAspect(subscriptionService, mock(UserContextService.class),
                new EntitlementPolicy(mode)));
        return factory.getProxy();
    }
}
