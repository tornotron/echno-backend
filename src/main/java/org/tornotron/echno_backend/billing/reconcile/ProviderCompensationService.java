package org.tornotron.echno_backend.billing.reconcile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.billing.gateway.BillingGateway;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.repositories.ProviderCompensationRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The durable side of "cancel the provider subscription we could not record". The row is
 * committed before the cancel is tried ({@link #record}), the outcome is written after it
 * ({@link #cancel}), and the reconciliation sweep calls {@link #retryDue} until the provider
 * has confirmed or the attempts are used up. Every write commits on its own so the caller's
 * failing transaction, if any, cannot take the record with it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderCompensationService {

    static final Duration FIRST_BACKOFF = Duration.ofMinutes(5);
    static final Duration MAX_BACKOFF = Duration.ofHours(6);

    private final ProviderCompensationRepository compensations;
    private final BillingGateway gateway;

    /** Records that the subscription needs cancelling; idempotent on (provider, subscription id). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProviderCompensation record(ProviderId provider, String providerSubscriptionId, Long organizationId, String reason) {
        return compensations.findByProviderAndProviderSubscriptionId(provider, providerSubscriptionId)
                .orElseGet(() -> {
                    Instant now = Instant.now();
                    try {
                        return compensations.saveAndFlush(ProviderCompensation.builder()
                                .provider(provider)
                                .providerSubscriptionId(providerSubscriptionId)
                                .organizationId(organizationId)
                                .reason(reason)
                                .createdAt(now)
                                .nextAttemptAt(now)
                                .build());
                    } catch (DataIntegrityViolationException raced) {
                        return compensations.findByProviderAndProviderSubscriptionId(provider, providerSubscriptionId)
                                .orElseThrow(() -> raced);
                    }
                });
    }

    /**
     * One cancel attempt against the provider, with the outcome written to the row.
     *
     * @return true when the provider confirmed and the row is resolved.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean cancel(Long compensationId) {
        ProviderCompensation row = compensations.findById(compensationId).orElse(null);
        if (row == null || row.isResolved()) {
            return row != null;
        }
        try {
            gateway.cancelSubscription(row.getProviderSubscriptionId(), false);
        } catch (RuntimeException e) {
            recordFailure(row, e);
            return false;
        }
        row.setAttemptCount(row.getAttemptCount() + 1);
        row.setLastError(null);
        row.setResolvedAt(Instant.now());
        compensations.save(row);
        log.info("Provider subscription {} cancelled after {} attempt(s); compensation {} resolved",
                row.getProviderSubscriptionId(), row.getAttemptCount(), row.getId());
        return true;
    }

    /** Retries the due rows, oldest first, up to {@code batch}; returns how many were attempted. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int retryDue(int batch) {
        List<ProviderCompensation> due = compensations.findDue(Instant.now(), ProviderCompensation.MAX_ATTEMPTS,
                PageRequest.of(0, Math.max(1, batch)));
        int attempted = 0;
        for (ProviderCompensation row : due) {
            try {
                cancel(row.getId());
            } catch (RuntimeException e) {
                log.error("Compensation {} for provider subscription {} could not be retried: {}",
                        row.getId(), row.getProviderSubscriptionId(), e.getMessage(), e);
            }
            attempted++;
        }
        return attempted;
    }

    private void recordFailure(ProviderCompensation row, RuntimeException cause) {
        int attempts = row.getAttemptCount() + 1;
        Duration wait = backoff(attempts);
        row.setAttemptCount(attempts);
        row.setLastError(cause.getMessage());
        row.setNextAttemptAt(Instant.now().plus(wait));
        compensations.save(row);
        if (attempts >= ProviderCompensation.MAX_ATTEMPTS) {
            log.error("Provider subscription {} could not be cancelled after {} attempts; compensation {} needs an operator: {}",
                    row.getProviderSubscriptionId(), attempts, row.getId(), cause.getMessage());
        } else {
            log.warn("Provider subscription {} could not be cancelled (attempt {}), next try in {}: {}",
                    row.getProviderSubscriptionId(), attempts, wait, cause.getMessage());
        }
    }

    /** 5 min, 10, 20, ... capped at 6 h. */
    static Duration backoff(int attempts) {
        long multiplier = 1L << Math.min(Math.max(attempts, 1) - 1, 10);
        Duration wait = FIRST_BACKOFF.multipliedBy(multiplier);
        return wait.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : wait;
    }
}
