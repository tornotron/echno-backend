package org.tornotron.echno_backend.billing.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.checkout.CheckoutRequiredException;
import org.tornotron.echno_backend.billing.checkout.CheckoutService;
import org.tornotron.echno_backend.billing.checkout.CheckoutSession;
import org.tornotron.echno_backend.billing.dto.CheckoutSessionDto;
import org.tornotron.echno_backend.billing.dto.PlanDto;
import org.tornotron.echno_backend.billing.dto.SubscriptionDto;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.BillingGateway;
import org.tornotron.echno_backend.billing.gateway.BillingGatewayProperties;
import org.tornotron.echno_backend.billing.gateway.BillingNotConfiguredException;
import org.tornotron.echno_backend.billing.gateway.NormalizedEventType;
import org.tornotron.echno_backend.billing.gateway.NormalizedSubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.gateway.dto.ChangePlanCommand;
import org.tornotron.echno_backend.billing.gateway.dto.GatewaySubscription;
import org.tornotron.echno_backend.billing.gateway.dto.NormalizedBillingEvent;
import org.tornotron.echno_backend.billing.repositories.CheckoutSessionRepository;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;
import org.tornotron.echno_backend.billing.webhook.EntitlementProjection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The org-side lifecycle never writes a provider-backed row directly (#806): create goes
 * through the checkout, change-plan and cancel go to the port and the projection applies the
 * provider's answer. Without a provider the direct path stays, for free plans.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionLifecycleServiceTest {

    private static final long ORG = 42L;
    private static final long USER = 7L;
    private static final String SUB = "sub_live_1";

    @Mock private SubscriptionService subscriptions;
    @Mock private CheckoutService checkout;
    @Mock private BillingGateway gateway;
    @Mock private EntitlementProjection projection;
    @Mock private PlanRepository plans;
    @Mock private CheckoutSessionRepository sessions;

    private final BillingGatewayProperties properties = new BillingGatewayProperties();
    private SubscriptionLifecycleService service;

    @BeforeEach
    void wire() {
        service = new SubscriptionLifecycleService(subscriptions, checkout, gateway, properties, projection, plans, sessions);
        Plan free = plan("starter", "0.00");
        Plan pro = plan("pro", "4999.00");
        when(plans.findByCodeWithFeatures("starter")).thenReturn(Optional.of(free));
        when(plans.findByCodeWithFeatures("pro")).thenReturn(Optional.of(pro));
        when(plans.findByCodeAndIsActiveTrue("starter")).thenReturn(Optional.of(free));
        when(plans.findByCodeAndIsActiveTrue("pro")).thenReturn(Optional.of(pro));
        when(subscriptions.createSubscription(anyLong(), any(), anyString(), any())).thenAnswer(inv ->
                dto("NONE", null, inv.getArgument(2), SubscriptionStatus.ACTIVE));
        when(subscriptions.changeSubscription(anyLong(), anyString())).thenAnswer(inv ->
                dto("NONE", null, inv.getArgument(1), SubscriptionStatus.ACTIVE));
        when(projection.apply(anyLong(), any())).thenReturn("projected");
    }

    private void razorpayWired() {
        when(gateway.isEnabled()).thenReturn(true);
        when(gateway.providerId()).thenReturn(ProviderId.RAZORPAY);
    }

    private void liveProviderRow() {
        when(subscriptions.getActiveSubscription(ORG)).thenReturn(Optional.of(dto("RAZORPAY", SUB, "pro", SubscriptionStatus.ACTIVE)));
        when(subscriptions.getProviderSubscription(ProviderId.RAZORPAY, SUB))
                .thenReturn(Optional.of(dto("RAZORPAY", SUB, "pro", SubscriptionStatus.ACTIVE)));
        when(sessions.findFirstByProviderAndProviderSubscriptionId(ProviderId.RAZORPAY, SUB)).thenReturn(Optional.of(
                CheckoutSession.builder().billingPeriod(BillingPeriod.MONTHLY).build()));
    }

    // -- subscribe --------------------------------------------------------------------------

    @Test
    void aFreePlanIsWrittenDirectlyWhateverTheProvider() {
        razorpayWired();
        SubscriptionDto created = service.subscribe(ORG, USER, "starter", BillingPeriod.MONTHLY);
        assertThat(created.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        verify(subscriptions).createSubscription(ORG, USER, "starter", BillingPeriod.MONTHLY);
        verify(checkout, never()).createSession(anyLong(), any(), any());
    }

    @Test
    void aPaidPlanWithAProviderGoesThroughTheCheckoutAndComesBackIncomplete() {
        razorpayWired();
        when(checkout.createSession(eq(ORG), eq(USER), any())).thenReturn(CheckoutSessionDto.builder().id(17L).providerSubscriptionId(SUB).build());
        when(subscriptions.getProviderSubscription(ProviderId.RAZORPAY, SUB))
                .thenReturn(Optional.of(dto("RAZORPAY", SUB, "pro", SubscriptionStatus.INCOMPLETE)));

        SubscriptionDto pending = service.subscribe(ORG, USER, "pro", BillingPeriod.MONTHLY);

        assertThat(pending.getStatus()).isEqualTo(SubscriptionStatus.INCOMPLETE);
        assertThat(pending.getProviderSubscriptionId()).isEqualTo(SUB);
        verify(subscriptions, never()).createSubscription(anyLong(), any(), anyString(), any());
    }

    @Test
    void aPaidPlanWithoutAProviderIsRefusedUnlessManualPaidRowsAreAllowed() {
        when(gateway.isEnabled()).thenReturn(false);
        assertThatThrownBy(() -> service.subscribe(ORG, USER, "pro", BillingPeriod.MONTHLY))
                .isInstanceOf(BillingNotConfiguredException.class);
        verify(subscriptions, never()).createSubscription(anyLong(), any(), anyString(), any());

        properties.setAllowManualPaid(true);
        SubscriptionDto manual = service.subscribe(ORG, USER, "pro", BillingPeriod.MONTHLY);
        assertThat(manual.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        verify(subscriptions).createSubscription(ORG, USER, "pro", BillingPeriod.MONTHLY);
    }

    // -- change plan ------------------------------------------------------------------------

    @Test
    void aProviderRowIsMovedAtTheProviderAndTheProjectionFollowsTheAnswer() {
        razorpayWired();
        liveProviderRow();
        GatewaySubscription answer = new GatewaySubscription(SUB, "plan_rzp_starter", "cust_1", NormalizedSubscriptionStatus.ACTIVE,
                Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS), null, null, null);
        when(gateway.changePlan(any())).thenReturn(answer);

        service.changePlan(ORG, "starter", false, true);

        ArgumentCaptor<ChangePlanCommand> cmd = ArgumentCaptor.forClass(ChangePlanCommand.class);
        verify(gateway).ensurePlan(any(), eq(BillingPeriod.MONTHLY));
        verify(gateway).changePlan(cmd.capture());
        assertThat(cmd.getValue().providerSubscriptionId()).isEqualTo(SUB);
        assertThat(cmd.getValue().newPlanCode()).isEqualTo("starter");
        ArgumentCaptor<NormalizedBillingEvent> event = ArgumentCaptor.forClass(NormalizedBillingEvent.class);
        verify(projection).apply(eq(ORG), event.capture());
        assertThat(event.getValue().subscription()).isSameAs(answer);
        assertThat(event.getValue().providerPlanId()).isEqualTo("plan_rzp_starter");
        assertThat(event.getValue().type()).isEqualTo(NormalizedEventType.SUBSCRIPTION_ACTIVATED);
        verify(subscriptions, never()).changeSubscription(anyLong(), anyString());
    }

    @Test
    void aManualRowCannotBeMovedToAPaidPlanDirectlyWhileAProviderIsWired() {
        razorpayWired();
        when(subscriptions.getActiveSubscription(ORG)).thenReturn(Optional.of(dto("NONE", null, "starter", SubscriptionStatus.ACTIVE)));

        assertThatThrownBy(() -> service.changePlan(ORG, "pro", false, true))
                .isInstanceOf(CheckoutRequiredException.class)
                .hasMessageContaining("checkout");
        verify(subscriptions, never()).changeSubscription(anyLong(), anyString());
        verify(gateway, never()).changePlan(any());

        service.changePlan(ORG, "pro", false, false);
        verify(subscriptions).changeSubscription(ORG, "pro");
    }

    @Test
    void aManualRowMovesToAFreePlanDirectly() {
        when(gateway.isEnabled()).thenReturn(false);
        when(subscriptions.getActiveSubscription(ORG)).thenReturn(Optional.of(dto("NONE", null, "pro", SubscriptionStatus.ACTIVE)));
        service.changePlan(ORG, "starter", false, true);
        verify(subscriptions).changeSubscription(ORG, "starter");
        verify(gateway, never()).changePlan(any());
    }

    // -- cancel -----------------------------------------------------------------------------

    @Test
    void aCancelReachesTheProviderAndTheRowFollowsTheAnswer() {
        razorpayWired();
        liveProviderRow();
        GatewaySubscription stillLive = new GatewaySubscription(SUB, "plan_rzp_pro", "cust_1", NormalizedSubscriptionStatus.ACTIVE,
                Instant.now(), Instant.now().plus(20, ChronoUnit.DAYS), null, null, null);
        when(gateway.cancelSubscription(SUB, true)).thenReturn(stillLive);

        service.cancel(ORG, false);

        verify(gateway).cancelSubscription(SUB, true);
        verify(subscriptions).markCancelRequested(ProviderId.RAZORPAY, SUB, true);
        ArgumentCaptor<NormalizedBillingEvent> event = ArgumentCaptor.forClass(NormalizedBillingEvent.class);
        verify(projection).apply(eq(ORG), event.capture());
        assertThat(event.getValue().subscription()).isSameAs(stillLive);
        verify(subscriptions, never()).cancelSubscription(anyLong(), any(Boolean.class));
    }

    @Test
    void anImmediateCancelTheProviderTookIsProjectedCanceledNotFlagged() {
        razorpayWired();
        liveProviderRow();
        GatewaySubscription cancelled = new GatewaySubscription(SUB, "plan_rzp_pro", "cust_1", NormalizedSubscriptionStatus.CANCELLED,
                null, null, null, null, null);
        when(gateway.cancelSubscription(SUB, false)).thenReturn(cancelled);

        service.cancel(ORG, true);

        verify(subscriptions).markCancelRequested(ProviderId.RAZORPAY, SUB, false);
        ArgumentCaptor<NormalizedBillingEvent> event = ArgumentCaptor.forClass(NormalizedBillingEvent.class);
        verify(projection).apply(eq(ORG), event.capture());
        assertThat(event.getValue().type()).isEqualTo(NormalizedEventType.SUBSCRIPTION_CANCELLED);
    }

    @Test
    void aManualRowIsCancelledDirectly() {
        when(subscriptions.getActiveSubscription(ORG)).thenReturn(Optional.of(dto("NONE", null, "starter", SubscriptionStatus.ACTIVE)));
        service.cancel(ORG, true);
        verify(subscriptions).cancelSubscription(ORG, true);
        verify(gateway, never()).cancelSubscription(anyString(), any(Boolean.class));
    }

    @Test
    void aProviderRowWithNoProviderWiredIsRefusedNotWrittenLocally() {
        when(gateway.isEnabled()).thenReturn(false);
        when(subscriptions.getActiveSubscription(ORG)).thenReturn(Optional.of(dto("RAZORPAY", SUB, "pro", SubscriptionStatus.ACTIVE)));
        assertThatThrownBy(() -> service.cancel(ORG, false)).isInstanceOf(BillingNotConfiguredException.class);
        assertThatThrownBy(() -> service.changePlan(ORG, "starter", false, true)).isInstanceOf(BillingNotConfiguredException.class);
        verify(subscriptions, never()).cancelSubscription(anyLong(), any(Boolean.class));
        verify(subscriptions, never()).changeSubscription(anyLong(), anyString());
    }

    // -- helpers ----------------------------------------------------------------------------

    private static Plan plan(String code, String monthly) {
        return Plan.builder().id(3L).code(code).name(code).monthlyPrice(new BigDecimal(monthly))
                .annualPrice(new BigDecimal(monthly).multiply(BigDecimal.TEN)).currency("INR").trialDays(0).build();
    }

    private static SubscriptionDto dto(String provider, String externalId, String planCode, SubscriptionStatus status) {
        Instant now = Instant.now();
        return SubscriptionDto.builder().id(101L).organizationId(ORG).provider(provider).providerSubscriptionId(externalId)
                .plan(PlanDto.builder().code(planCode).build()).status(status)
                .currentPeriodStart(now).currentPeriodEnd(now.plus(30, ChronoUnit.DAYS)).build();
    }
}
