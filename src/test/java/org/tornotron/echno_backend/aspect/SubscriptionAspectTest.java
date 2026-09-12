package org.tornotron.echno_backend.aspect;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.tornotron.echno_backend.billing.dto.FeatureAccessResultDto;
import org.tornotron.echno_backend.billing.entitlement.EntitlementPolicy;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.common.exception.SubscriptionAccessDeniedException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.user.UserContextService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The gate as a proxy would apply it, without a Spring context: the two advices, which of
 * them answers for a given method, and what the policy makes of a refusal.
 */
class SubscriptionAspectTest {

    private static final Long ORG = 31L;
    private static final Long USER = 8L;
    private static final String MODULE = "MODULE_INSPECTIONS";
    private static final String EXPORT = "REPORT_EXPORT";

    @RequireSubscription(feature = MODULE)
    public static class GatedController {
        public String list() { return "listed"; }

        @RequireSubscription(feature = EXPORT, recordUsage = true)
        public String export() { return "exported"; }
    }

    public static class MethodGatedController {
        @RequireSubscription(feature = EXPORT)
        public String export() { return "exported"; }

        public String open() { return "open"; }
    }

    private final SubscriptionService subscriptionService = mock(SubscriptionService.class);
    private final UserContextService userContextService = mock(UserContextService.class);
    private final ListAppender<ILoggingEvent> policyLog = new ListAppender<>();

    @BeforeEach
    void tenant() {
        TenantContext.setCurrentOrgId(ORG);
        when(userContextService.getCurrentUserId()).thenReturn(USER);
        policyLog.start();
        ((Logger) LoggerFactory.getLogger(EntitlementPolicy.class)).addAppender(policyLog);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        ((Logger) LoggerFactory.getLogger(EntitlementPolicy.class)).detachAppender(policyLog);
    }

    private <T> T proxy(T target, String mode) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new SubscriptionAspect(subscriptionService, userContextService, new EntitlementPolicy(mode)));
        return factory.getProxy();
    }

    @Test
    void classLevelAnnotationGatesEveryMethod() {
        when(subscriptionService.checkFeatureAccess(ORG, MODULE)).thenReturn(FeatureAccessResultDto.noSubscription());
        GatedController controller = proxy(new GatedController(), "enforce");

        assertThatExceptionOfType(SubscriptionAccessDeniedException.class)
                .isThrownBy(controller::list)
                .satisfies(ex -> assertThat(ex.getFeatureCode()).isEqualTo(MODULE));
        verify(subscriptionService, times(1)).checkFeatureAccess(anyLong(), any());
    }

    @Test
    void methodLevelAnnotationWinsOverClassLevelAndIsEvaluatedOnce() {
        when(subscriptionService.checkFeatureAccess(ORG, EXPORT)).thenReturn(FeatureAccessResultDto.allowed());
        GatedController controller = proxy(new GatedController(), "enforce");

        assertThat(controller.export()).isEqualTo("exported");

        verify(subscriptionService, times(1)).checkFeatureAccess(anyLong(), any());
        verify(subscriptionService).checkFeatureAccess(ORG, EXPORT);
        verify(subscriptionService, never()).checkFeatureAccess(ORG, MODULE);
        verify(subscriptionService).recordUsage(ORG, USER, EXPORT, 1L);
    }

    @Test
    void methodLevelAnnotationOnAnUnannotatedClassStillGates() {
        when(subscriptionService.checkFeatureAccess(ORG, EXPORT)).thenReturn(FeatureAccessResultDto.featureNotInPlan());
        MethodGatedController controller = proxy(new MethodGatedController(), "enforce");

        assertThatExceptionOfType(SubscriptionAccessDeniedException.class).isThrownBy(controller::export);
        assertThat(controller.open()).isEqualTo("open");
        verify(subscriptionService, times(1)).checkFeatureAccess(anyLong(), any());
    }

    @Test
    void advisoryModeAllowsTheCallAndLogsTheRefusal() {
        when(subscriptionService.checkFeatureAccess(ORG, MODULE)).thenReturn(FeatureAccessResultDto.noSubscription());
        GatedController controller = proxy(new GatedController(), "advisory");

        assertThat(controller.list()).isEqualTo("listed");

        assertThat(policyLog.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .contains("organization " + ORG)
                            .contains(MODULE)
                            .contains("No active subscription");
                });
    }

    @Test
    void enforceModeDeniesWithTheStandardReason() {
        when(subscriptionService.checkFeatureAccess(ORG, MODULE)).thenReturn(FeatureAccessResultDto.featureNotInPlan());
        GatedController controller = proxy(new GatedController(), "enforce");

        assertThatExceptionOfType(SubscriptionAccessDeniedException.class)
                .isThrownBy(controller::list)
                .satisfies(ex -> {
                    assertThat(ex.getAccessResult().getReason()).isEqualTo("Feature not included in current plan");
                    assertThat(ex.getMessage()).isEqualTo("Upgrade to higher tier plan");
                });
        assertThat(policyLog.list).isEmpty();
    }

    @Test
    void noOrganizationInContextIsRefusedInEnforceMode() {
        TenantContext.clear();
        GatedController controller = proxy(new GatedController(), "enforce");

        assertThatExceptionOfType(SubscriptionAccessDeniedException.class)
                .isThrownBy(controller::list)
                .satisfies(ex -> assertThat(ex.getAccessResult().getReason()).isEqualTo("No organization in context"));
        verify(subscriptionService, never()).checkFeatureAccess(anyLong(), eq(MODULE));
    }
}
