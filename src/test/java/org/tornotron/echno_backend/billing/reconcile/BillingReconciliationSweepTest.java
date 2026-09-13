package org.tornotron.echno_backend.billing.reconcile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.Subscription;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.BillingGateway;
import org.tornotron.echno_backend.billing.gateway.BillingGatewayException;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.billing.webhook.BillingReconciliationService;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What the reconciliation sweep does with a provider, without one, and when one organization
 * fails: it is a no-op with provider none, runs each stale row pinned to its own organization,
 * and a failing row costs the others nothing.
 */
@ExtendWith(MockitoExtension.class)
class BillingReconciliationSweepTest {

    @Mock
    private BillingGateway gateway;
    @Mock
    private SubscriptionRepository subscriptions;
    @Mock
    private BillingReconciliationService reconciliation;

    private BillingReconciliationSweep sweep;

    @BeforeEach
    void setUp() {
        sweep = new BillingReconciliationSweep(gateway, subscriptions, reconciliation,
                new TenantScopedJobRunner(), new BillingReconcileProperties());
    }

    private static Subscription stale(Long id, Long orgId, String externalId) {
        return Subscription.builder().id(id).organizationId(orgId).provider(ProviderId.RAZORPAY)
                .externalSubscriptionId(externalId).status(SubscriptionStatus.ACTIVE)
                .plan(Plan.builder().id(1L).code("pro").build())
                .currentPeriodStart(Instant.now().minus(40, ChronoUnit.DAYS))
                .currentPeriodEnd(Instant.now().minus(10, ChronoUnit.DAYS)).build();
    }

    @Test
    void providerNone_isANoOp() {
        when(gateway.isEnabled()).thenReturn(false);

        BillingReconciliationSweep.Summary summary = sweep.runPass();

        assertThat(summary).isEqualTo(BillingReconciliationSweep.Summary.NONE);
        verifyNoInteractions(subscriptions, reconciliation);
    }

    @Test
    void runsEachStaleRowPinnedToItsOrganization_thenRetriesTheInbox() {
        when(gateway.isEnabled()).thenReturn(true);
        when(gateway.providerId()).thenReturn(ProviderId.RAZORPAY);
        when(subscriptions.findStaleProviderSubscriptions(eq(ProviderId.RAZORPAY), anyList(), any(), any(), any(), eq(PageRequest.of(0, 200))))
                .thenReturn(List.of(stale(1L, 10L, "sub_a"), stale(2L, 20L, "sub_b"), stale(3L, 10L, "sub_c")));
        List<Long> tenantsSeen = new ArrayList<>();
        when(reconciliation.reconcile(anyString())).thenAnswer(call -> {
            tenantsSeen.add(TenantContext.getCurrentOrgId());
            return "ACTIVE -> ACTIVE";
        });
        when(reconciliation.retryPending(anyInt())).thenReturn(4);

        BillingReconciliationSweep.Summary summary = sweep.runPass();

        assertThat(summary).isEqualTo(new BillingReconciliationSweep.Summary(2, 3, 0, 4));
        verify(reconciliation).reconcile("sub_a");
        verify(reconciliation).reconcile("sub_b");
        verify(reconciliation).reconcile("sub_c");
        assertThat(tenantsSeen).containsExactly(10L, 10L, 20L);
        verify(reconciliation).retryPending(100);
        assertThat(TenantContext.getCurrentOrgId()).isNull();
    }

    @Test
    void oneFailingRow_doesNotStopThePass() {
        when(gateway.isEnabled()).thenReturn(true);
        when(gateway.providerId()).thenReturn(ProviderId.RAZORPAY);
        when(subscriptions.findStaleProviderSubscriptions(eq(ProviderId.RAZORPAY), anyList(), any(), any(), any(), any()))
                .thenReturn(List.of(stale(1L, 10L, "sub_a"), stale(2L, 20L, "sub_b")));
        when(reconciliation.reconcile("sub_a")).thenThrow(new BillingGatewayException("provider 503"));
        when(reconciliation.reconcile("sub_b")).thenReturn("PAST_DUE -> ACTIVE");

        BillingReconciliationSweep.Summary summary = sweep.runPass();

        assertThat(summary.reconciled()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        verify(reconciliation).reconcile("sub_b");
    }

    @Test
    void capsTheRowsOnePassFetches_inTheQuery() {
        BillingReconcileProperties properties = new BillingReconcileProperties();
        properties.setMaxPerRun(1);
        sweep = new BillingReconciliationSweep(gateway, subscriptions, reconciliation, new TenantScopedJobRunner(), properties);
        when(gateway.isEnabled()).thenReturn(true);
        when(gateway.providerId()).thenReturn(ProviderId.RAZORPAY);
        when(subscriptions.findStaleProviderSubscriptions(eq(ProviderId.RAZORPAY), anyList(), any(), any(), any(), eq(PageRequest.of(0, 1))))
                .thenReturn(List.of(stale(1L, 10L, "sub_a")));
        when(reconciliation.reconcile("sub_a")).thenReturn("ok");

        sweep.runPass();

        verify(reconciliation).reconcile("sub_a");
        verify(reconciliation, never()).reconcile("sub_b");
    }
}
