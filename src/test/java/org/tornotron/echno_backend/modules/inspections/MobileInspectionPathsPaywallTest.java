package org.tornotron.echno_backend.modules.inspections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.tornotron.echno_backend.aspect.SubscriptionAspect;
import org.tornotron.echno_backend.billing.dto.FeatureAccessResultDto;
import org.tornotron.echno_backend.billing.entitlement.EntitlementPolicy;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.common.exception.SubscriptionAccessDeniedException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.inspections.pdf.InspectionReportPdfService;
import org.tornotron.echno_backend.modules.inspections.service.DefectAnnotationService;
import org.tornotron.echno_backend.modules.inspections.service.InspectionEvidenceService;
import org.tornotron.echno_backend.modules.inspections.service.InspectionService;
import org.tornotron.echno_backend.modules.inspections.service.ReinspectionService;
import org.tornotron.echno_backend.modules.inspections.web.InspectionController;
import org.tornotron.echno_backend.modules.inspections.web.ReinspectionController;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The paywall reaches the mobile inspection paths (#998), through the real aspect.
 *
 * <p>{@code @RequireSubscription} is not {@code @Inherited}, and the aspect matches on the type
 * that declares the method being run, so both ways a mobile controller is built are checked: an
 * inherited handler ({@link InspectionController}) and an overriding one
 * ({@link ReinspectionController}). Either could otherwise leave the module open on the mobile
 * path to an organization whose plan does not include it.
 */
class MobileInspectionPathsPaywallTest {

    private static final Long DARK_ORG = 31L;
    private static final Long LIT_ORG = 32L;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static <T> T gated(T target) {
        SubscriptionService subscriptionService = mock(SubscriptionService.class);
        when(subscriptionService.checkFeatureAccess(DARK_ORG, InspectionsModule.FEATURE_KEY))
                .thenReturn(FeatureAccessResultDto.featureNotInPlan());
        when(subscriptionService.checkFeatureAccess(LIT_ORG, InspectionsModule.FEATURE_KEY))
                .thenReturn(FeatureAccessResultDto.allowed());
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new SubscriptionAspect(subscriptionService, mock(UserContextService.class),
                new EntitlementPolicy("enforce")));
        return factory.getProxy();
    }

    @Test
    void anInheritedHandlerIsRefusedToAnOrganizationWithoutTheModule() {
        InspectionService service = mock(InspectionService.class);
        InspectionController controller = gated(new InspectionController(service,
                mock(DefectAnnotationService.class), mock(InspectionEvidenceService.class),
                mock(InspectionReportPdfService.class)));
        TenantContext.setCurrentOrgId(DARK_ORG);

        assertThatExceptionOfType(SubscriptionAccessDeniedException.class)
                .isThrownBy(() -> controller.get(UUID.randomUUID()));
        verifyNoInteractions(service);
    }

    @Test
    void anOverridingHandlerIsRefusedToAnOrganizationWithoutTheModule() {
        ReinspectionService service = mock(ReinspectionService.class);
        ReinspectionController controller = gated(new ReinspectionController(service));
        TenantContext.setCurrentOrgId(DARK_ORG);

        assertThatExceptionOfType(SubscriptionAccessDeniedException.class)
                .isThrownBy(() -> controller.get(UUID.randomUUID()));
        verifyNoInteractions(service);
    }

    @Test
    void anEntitledOrganizationIsServedOnTheMobilePath() {
        ReinspectionService service = mock(ReinspectionService.class);
        ReinspectionController controller = gated(new ReinspectionController(service));
        TenantContext.setCurrentOrgId(LIT_ORG);
        UUID id = UUID.randomUUID();

        controller.get(id);

        verify(service).findById(id);
    }
}
