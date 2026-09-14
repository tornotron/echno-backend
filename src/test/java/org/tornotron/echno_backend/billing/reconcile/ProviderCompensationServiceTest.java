package org.tornotron.echno_backend.billing.reconcile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.billing.gateway.BillingGateway;
import org.tornotron.echno_backend.billing.gateway.BillingGatewayException;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.repositories.ProviderCompensationRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The compensation outbox: the row is written before the cancel, a failed cancel is
 * recorded with backoff instead of lost, and the sweep's retry resolves it once the provider
 * confirms.
 */
@ExtendWith(MockitoExtension.class)
class ProviderCompensationServiceTest {

    @Mock private ProviderCompensationRepository compensations;
    @Mock private BillingGateway gateway;

    private ProviderCompensationService service;

    @BeforeEach
    void setUp() {
        service = new ProviderCompensationService(compensations, gateway);
    }

    private static ProviderCompensation row(long id, int attempts) {
        return ProviderCompensation.builder().id(id).provider(ProviderId.RAZORPAY).providerSubscriptionId("sub_" + id)
                .organizationId(4242L).attemptCount(attempts).createdAt(Instant.now()).nextAttemptAt(Instant.now()).build();
    }

    @Test
    void record_writesTheRowOnceAndReturnsTheExistingOneAfterThat() {
        when(compensations.findByProviderAndProviderSubscriptionId(ProviderId.RAZORPAY, "sub_1")).thenReturn(Optional.empty());
        when(compensations.saveAndFlush(any())).thenAnswer(inv -> {
            ProviderCompensation r = inv.getArgument(0);
            r.setId(1L);
            return r;
        });

        ProviderCompensation first = service.record(ProviderId.RAZORPAY, "sub_1", 4242L, "checkout write failed");
        assertThat(first.getId()).isEqualTo(1L);
        assertThat(first.getAttemptCount()).isZero();
        assertThat(first.getNextAttemptAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(first.isResolved()).isFalse();

        when(compensations.findByProviderAndProviderSubscriptionId(ProviderId.RAZORPAY, "sub_1")).thenReturn(Optional.of(first));
        assertThat(service.record(ProviderId.RAZORPAY, "sub_1", 4242L, "again")).isSameAs(first);
        verify(compensations).saveAndFlush(any());
    }

    @Test
    void record_underARaceReadsTheRowTheOtherWriterCommitted() {
        ProviderCompensation theirs = row(9L, 0);
        when(compensations.findByProviderAndProviderSubscriptionId(ProviderId.RAZORPAY, "sub_9"))
                .thenReturn(Optional.empty(), Optional.of(theirs));
        when(compensations.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_billing_compensation_subscription"));

        assertThat(service.record(ProviderId.RAZORPAY, "sub_9", 4242L, "x")).isSameAs(theirs);
    }

    @Test
    void cancel_thatTheProviderConfirmsResolvesTheRow() {
        ProviderCompensation row = row(1L, 0);
        when(compensations.findById(1L)).thenReturn(Optional.of(row));

        assertThat(service.cancel(1L)).isTrue();

        verify(gateway).cancelSubscription("sub_1", false);
        assertThat(row.isResolved()).isTrue();
        assertThat(row.getAttemptCount()).isEqualTo(1);
        assertThat(row.getLastError()).isNull();
        verify(compensations).save(row);
    }

    @Test
    void cancel_thatFailsIsRecordedWithBackoffInsteadOfLost() {
        ProviderCompensation row = row(2L, 0);
        when(compensations.findById(2L)).thenReturn(Optional.of(row));
        doThrow(new BillingGatewayException("razorpay 503")).when(gateway).cancelSubscription("sub_2", false);
        Instant before = Instant.now();

        assertThat(service.cancel(2L)).isFalse();

        assertThat(row.isResolved()).isFalse();
        assertThat(row.getAttemptCount()).isEqualTo(1);
        assertThat(row.getLastError()).isEqualTo("razorpay 503");
        assertThat(row.getNextAttemptAt()).isAfterOrEqualTo(before.plus(ProviderCompensationService.FIRST_BACKOFF));
        verify(compensations).save(row);
    }

    @Test
    void cancel_ofAResolvedOrMissingRowDoesNotTouchTheProvider() {
        ProviderCompensation done = row(3L, 1);
        done.setResolvedAt(Instant.now());
        when(compensations.findById(3L)).thenReturn(Optional.of(done));
        when(compensations.findById(4L)).thenReturn(Optional.empty());

        assertThat(service.cancel(3L)).isTrue();
        assertThat(service.cancel(4L)).isFalse();
        verify(gateway, never()).cancelSubscription(any(), eq(false));
    }

    @Test
    void retryDue_attemptsEachDueRowAndOneFailureDoesNotEndTheBatch() {
        ProviderCompensation a = row(5L, 1);
        ProviderCompensation b = row(6L, 2);
        when(compensations.findDue(any(), eq(ProviderCompensation.MAX_ATTEMPTS), eq(PageRequest.of(0, 50)))).thenReturn(List.of(a, b));
        when(compensations.findById(5L)).thenReturn(Optional.of(a));
        when(compensations.findById(6L)).thenReturn(Optional.of(b));
        doThrow(new BillingGatewayException("still down")).when(gateway).cancelSubscription("sub_5", false);

        assertThat(service.retryDue(50)).isEqualTo(2);

        assertThat(a.isResolved()).isFalse();
        assertThat(a.getAttemptCount()).isEqualTo(2);
        assertThat(b.isResolved()).isTrue();
    }

    @Test
    void backoff_doublesFromFiveMinutesAndCapsAtSixHours() {
        assertThat(ProviderCompensationService.backoff(1)).isEqualTo(Duration.ofMinutes(5));
        assertThat(ProviderCompensationService.backoff(2)).isEqualTo(Duration.ofMinutes(10));
        assertThat(ProviderCompensationService.backoff(4)).isEqualTo(Duration.ofMinutes(40));
        assertThat(ProviderCompensationService.backoff(8)).isEqualTo(Duration.ofHours(6));
        assertThat(ProviderCompensationService.backoff(40)).isEqualTo(Duration.ofHours(6));
    }
}
