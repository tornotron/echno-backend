package org.tornotron.echno_backend.billing.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.Subscription;
import org.tornotron.echno_backend.billing.checkout.CheckoutSession;
import org.tornotron.echno_backend.billing.checkout.CheckoutSessionStatus;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.BillingGateway;
import org.tornotron.echno_backend.billing.gateway.NormalizedSubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.gateway.dto.GatewaySubscription;
import org.tornotron.echno_backend.billing.gateway.dto.NormalizedBillingEvent;
import org.tornotron.echno_backend.billing.repositories.BillingEventRepository;
import org.tornotron.echno_backend.billing.repositories.CheckoutSessionRepository;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The sweep's per-row repair: re-send a cancel the provider ignored, expire an abandoned checkout. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BillingReconciliationServiceTest {

    private static final long ORG = 42L;
    private static final String SUB = "sub_rec_1";

    @Mock private BillingGateway gateway;
    @Mock private SubscriptionRepository subscriptions;
    @Mock private BillingEventRepository events;
    @Mock private EntitlementProjection projection;
    @Mock private BillingEventProjector projector;
    @Mock private CheckoutSessionRepository sessions;

    private BillingReconciliationService service;

    @BeforeEach
    void wire() {
        service = new BillingReconciliationService(gateway, subscriptions, events, projection, projector, sessions);
        when(gateway.isEnabled()).thenReturn(true);
        when(gateway.providerId()).thenReturn(ProviderId.RAZORPAY);
        when(projection.apply(any(), any())).thenReturn("projected");
    }

    private Subscription row(SubscriptionStatus status) {
        Instant now = Instant.now();
        Subscription row = Subscription.builder().id(5L).organizationId(ORG).plan(Plan.builder().code("pro").build())
                .provider(ProviderId.RAZORPAY).externalSubscriptionId(SUB).status(status)
                .currentPeriodStart(now.minus(40, ChronoUnit.DAYS)).currentPeriodEnd(now.minus(10, ChronoUnit.DAYS)).build();
        when(subscriptions.findByProviderAndExternalSubscriptionId(ProviderId.RAZORPAY, SUB)).thenReturn(Optional.of(row));
        return row;
    }

    private static GatewaySubscription snapshot(NormalizedSubscriptionStatus status) {
        return new GatewaySubscription(SUB, "plan_rzp", "cust_1", status, null, null, null, null, null);
    }

    @Test
    void aCancelTheProviderDidNotTakeIsReSentAndTheRowNeverReactivated() {
        Subscription row = row(SubscriptionStatus.CANCELED);
        row.setCancelRequestedAt(Instant.now().minus(2, ChronoUnit.DAYS));
        when(gateway.fetchSubscription(SUB)).thenReturn(snapshot(NormalizedSubscriptionStatus.ACTIVE));
        when(gateway.cancelSubscription(SUB, false)).thenReturn(snapshot(NormalizedSubscriptionStatus.CANCELLED));

        service.reconcile(SUB);

        verify(gateway).cancelSubscription(SUB, false);
        ArgumentCaptor<NormalizedBillingEvent> event = ArgumentCaptor.forClass(NormalizedBillingEvent.class);
        verify(projection).apply(eq(ORG), event.capture());
        assertThat(event.getValue().subscription().status()).isEqualTo(NormalizedSubscriptionStatus.CANCELLED);
    }

    @Test
    void aRowCancelledOnAMandateEventIsRepairedFromTheProviderNotReCancelled() {
        Subscription row = row(SubscriptionStatus.CANCELED);
        row.setCancellationReason("Mandate token_x revoked");
        when(gateway.fetchSubscription(SUB)).thenReturn(snapshot(NormalizedSubscriptionStatus.ACTIVE));

        service.reconcile(SUB);

        verify(gateway, never()).cancelSubscription(anyString(), any(Boolean.class));
        ArgumentCaptor<NormalizedBillingEvent> event = ArgumentCaptor.forClass(NormalizedBillingEvent.class);
        verify(projection).apply(eq(ORG), event.capture());
        assertThat(event.getValue().subscription().status()).isEqualTo(NormalizedSubscriptionStatus.ACTIVE);
    }

    @Test
    void anAbandonedCheckoutPastItsWindowIsExpiredLocallyWithItsSession() {
        Subscription row = row(SubscriptionStatus.INCOMPLETE);
        CheckoutSession session = CheckoutSession.builder().id(9L).provider(ProviderId.RAZORPAY).providerSubscriptionId(SUB)
                .status(CheckoutSessionStatus.OPEN).expiresAt(Instant.now().minus(3, ChronoUnit.HOURS)).build();
        when(sessions.findFirstByProviderAndProviderSubscriptionId(ProviderId.RAZORPAY, SUB)).thenReturn(Optional.of(session));
        when(gateway.fetchSubscription(SUB)).thenReturn(snapshot(NormalizedSubscriptionStatus.CREATED));

        service.reconcile(SUB);

        ArgumentCaptor<NormalizedBillingEvent> event = ArgumentCaptor.forClass(NormalizedBillingEvent.class);
        verify(projection).apply(eq(ORG), event.capture());
        assertThat(event.getValue().subscription().status()).isEqualTo(NormalizedSubscriptionStatus.EXPIRED_BEFORE_AUTH);
        assertThat(session.getStatus()).isEqualTo(CheckoutSessionStatus.EXPIRED);
        verify(sessions).save(session);
    }

    @Test
    void anIncompleteCheckoutStillInsideItsWindowIsLeftPending() {
        row(SubscriptionStatus.INCOMPLETE);
        CheckoutSession session = CheckoutSession.builder().id(9L).provider(ProviderId.RAZORPAY).providerSubscriptionId(SUB)
                .status(CheckoutSessionStatus.OPEN).expiresAt(Instant.now().plus(10, ChronoUnit.MINUTES)).build();
        when(sessions.findFirstByProviderAndProviderSubscriptionId(ProviderId.RAZORPAY, SUB)).thenReturn(Optional.of(session));
        when(gateway.fetchSubscription(SUB)).thenReturn(snapshot(NormalizedSubscriptionStatus.CREATED));

        service.reconcile(SUB);

        ArgumentCaptor<NormalizedBillingEvent> event = ArgumentCaptor.forClass(NormalizedBillingEvent.class);
        verify(projection).apply(eq(ORG), event.capture());
        assertThat(event.getValue().subscription().status()).isEqualTo(NormalizedSubscriptionStatus.CREATED);
        assertThat(session.getStatus()).isEqualTo(CheckoutSessionStatus.OPEN);
    }
}
