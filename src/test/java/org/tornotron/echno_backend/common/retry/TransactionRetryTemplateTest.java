package org.tornotron.echno_backend.common.retry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.tornotron.echno_backend.common.exception.TransactionRetriesExhaustedException;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link TransactionRetryTemplate}.
 *
 * <p>The behaviour that matters is not that the work runs again, it is that it runs again in a
 * <em>new</em> transaction: a serializable abort dooms the transaction it happened in, so
 * repeating the work on the same one would fail identically. The tests therefore drive the real
 * {@link TransactionalWorkRunner} through a real Spring {@link TransactionInterceptor} over a
 * transaction manager that counts what it is asked to do, which shows each attempt beginning its
 * own transaction and the failed ones rolling back. No application context is involved: the
 * proxy is built by hand, which is both faster and keeps the test JVM's heap free for the suites
 * that genuinely need a context.
 *
 * <p>Backoff is configured to zero here so the retries do not add wall-clock time to the build;
 * the jitter maths is bounded arithmetic and is exercised in production by the real defaults.
 * The one exception is the extreme-bounds test, which has to let a real pause happen to show
 * that the clamped ceiling both keeps the pause short and keeps it legal.
 */
class TransactionRetryTemplateTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final String OPERATION = "TestService.doWork";

    private MeterRegistry meterRegistry;
    private CountingTransactionManager transactionManager;
    private TransactionRetryTemplate template;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        transactionManager = new CountingTransactionManager();
        template = new TransactionRetryTemplate(
                transactionalRunner(transactionManager), meterRegistry, MAX_ATTEMPTS, 0L, 0L);
    }

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void restartsTheTransactionAfterASerializationFailureAndSucceeds() {
        AtomicInteger attempts = new AtomicInteger();

        String result = template.execute(OPERATION, () -> {
            if (attempts.incrementAndGet() == 1) {
                throw serializationFailure();
            }
            return "committed";
        });

        assertThat(result).isEqualTo("committed");
        assertThat(attempts).hasValue(2);
        // The point of the fix: two transactions, the aborted one rolled back and the retry
        // committed, rather than two attempts sharing one doomed transaction.
        assertThat(transactionManager.begun).isEqualTo(2);
        assertThat(transactionManager.rolledBack).isEqualTo(1);
        assertThat(transactionManager.committed).isEqualTo(1);
        assertThat(counter("echno.transaction.retry.attempts")).isEqualTo(1.0);
        assertThat(counter("echno.transaction.retry.exhausted")).isZero();
    }

    @Test
    void keepsRestartingUpToTheAttemptLimit() {
        AtomicInteger attempts = new AtomicInteger();

        String result = template.execute(OPERATION, () -> {
            if (attempts.incrementAndGet() < MAX_ATTEMPTS) {
                throw serializationFailure();
            }
            return "committed";
        });

        assertThat(result).isEqualTo("committed");
        assertThat(transactionManager.begun).isEqualTo(MAX_ATTEMPTS);
        assertThat(counter("echno.transaction.retry.attempts")).isEqualTo(MAX_ATTEMPTS - 1.0);
    }

    @Test
    void reportsAConflictOnceTheAttemptsAreUsedUp() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatExceptionOfType(TransactionRetriesExhaustedException.class)
                .isThrownBy(() -> template.execute(OPERATION, () -> {
                    attempts.incrementAndGet();
                    throw serializationFailure();
                }))
                .withMessageContaining(String.valueOf(MAX_ATTEMPTS))
                .withCauseInstanceOf(CannotAcquireLockException.class);

        assertThat(attempts).hasValue(MAX_ATTEMPTS);
        assertThat(transactionManager.begun).isEqualTo(MAX_ATTEMPTS);
        assertThat(transactionManager.rolledBack).isEqualTo(MAX_ATTEMPTS);
        assertThat(transactionManager.committed).isZero();
        assertThat(counter("echno.transaction.retry.exhausted")).isEqualTo(1.0);
    }

    @Test
    void doesNotRetryAFailureThatIsNotASerializationConflict() {
        AtomicInteger attempts = new AtomicInteger();
        DataIntegrityViolationException duplicateKey = new DataIntegrityViolationException(
                "duplicate key value", new SQLException("duplicate key value", "23505"));

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> template.execute(OPERATION, () -> {
                    attempts.incrementAndGet();
                    throw duplicateKey;
                }))
                .isSameAs(duplicateKey);

        assertThat(attempts).hasValue(1);
        assertThat(transactionManager.begun).isEqualTo(1);
        assertThat(counter("echno.transaction.retry.attempts")).isZero();
        assertThat(counter("echno.transaction.retry.exhausted")).isZero();
    }

    @Test
    void doesNotRetryInsideACallersTransaction() {
        // Nothing to restart: the boundary belongs to the caller and their transaction is
        // already doomed, so the abort has to travel out to whoever opened it.
        TransactionSynchronizationManager.setActualTransactionActive(true);
        AtomicInteger attempts = new AtomicInteger();

        assertThatExceptionOfType(CannotAcquireLockException.class)
                .isThrownBy(() -> template.execute(OPERATION, () -> {
                    attempts.incrementAndGet();
                    throw serializationFailure();
                }));

        assertThat(attempts).hasValue(1);
        assertThat(counter("echno.transaction.retry.attempts")).isZero();
    }

    @Test
    void retriesTheNoResultForm() {
        AtomicInteger attempts = new AtomicInteger();

        template.executeWithoutResult(OPERATION, () -> {
            if (attempts.incrementAndGet() == 1) {
                throw serializationFailure();
            }
        });

        assertThat(attempts).hasValue(2);
        assertThat(transactionManager.begun).isEqualTo(2);
        assertThat(transactionManager.committed).isEqualTo(1);
    }

    @Test
    void stillRetriesWhenTheBackoffBoundsAreConfiguredAtTheirExtreme() {
        // Long.MAX_VALUE on both bounds used to make the jitter ceiling overflow when it was
        // incremented, so ThreadLocalRandom rejected the bound and the IllegalArgumentException
        // replaced the serialization failure on its way out of the catch block: a retryable
        // abort surfacing as a 500, which is what this template exists to prevent. The bounds
        // are clamped at construction now, so the retry happens and the pause stays bounded.
        TransactionRetryTemplate extreme = new TransactionRetryTemplate(
                transactionalRunner(transactionManager), meterRegistry, MAX_ATTEMPTS,
                Long.MAX_VALUE, Long.MAX_VALUE);
        AtomicInteger attempts = new AtomicInteger();

        long startedAt = System.nanoTime();
        String result = extreme.execute(OPERATION, () -> {
            if (attempts.incrementAndGet() == 1) {
                throw serializationFailure();
            }
            return "committed";
        });
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(result).isEqualTo("committed");
        assertThat(attempts).hasValue(2);
        assertThat(transactionManager.begun).isEqualTo(2);
        // The clamp is what keeps this from being a geological sleep as well as a throw; the
        // slack is generous because the assertion is about the bound, not about scheduling.
        assertThat(elapsedMillis)
                .isLessThanOrEqualTo(TransactionRetryTemplate.MAX_CONFIGURABLE_BACKOFF_MILLIS + 10_000L);
    }

    @Test
    void clampsABackoffTooLongForARequestThreadToWait() {
        long cap = TransactionRetryTemplate.MAX_CONFIGURABLE_BACKOFF_MILLIS;

        // The shipped defaults and anything else sensible pass through untouched.
        assertThat(TransactionRetryTemplate.clampBackoff("initial-backoff-millis", 25L)).isEqualTo(25L);
        assertThat(TransactionRetryTemplate.clampBackoff("max-backoff-millis", 250L)).isEqualTo(250L);
        assertThat(TransactionRetryTemplate.clampBackoff("max-backoff-millis", cap)).isEqualTo(cap);
        assertThat(TransactionRetryTemplate.clampBackoff("max-backoff-millis", 0L)).isZero();

        // A plausible typo in an environment override: an initial pause of roughly 31 years.
        assertThat(TransactionRetryTemplate.clampBackoff("initial-backoff-millis",
                1_000_000_000_000_000_000L)).isEqualTo(cap);
        assertThat(TransactionRetryTemplate.clampBackoff("max-backoff-millis", Long.MAX_VALUE))
                .isEqualTo(cap);

        // The floor that was already there stays.
        assertThat(TransactionRetryTemplate.clampBackoff("initial-backoff-millis", -1L)).isZero();
        assertThat(TransactionRetryTemplate.clampBackoff("initial-backoff-millis", Long.MIN_VALUE))
                .isZero();
    }

    /** A CockroachDB serializable abort in the shape Spring hands to application code. */
    private static CannotAcquireLockException serializationFailure() {
        return new CannotAcquireLockException("Unable to commit against JDBC Connection",
                new SQLException("restart transaction: retry txn (RETRY_SERIALIZABLE)", "40001"));
    }

    private double counter(String name) {
        return meterRegistry.find(name).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    /**
     * The real runner behind the real transaction interceptor, so the {@code @Transactional}
     * boundary under test is Spring's own and not a stand-in.
     */
    private static TransactionalWorkRunner transactionalRunner(PlatformTransactionManager manager) {
        ProxyFactory factory = new ProxyFactory(new TransactionalWorkRunner());
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (TransactionalWorkRunner) factory.getProxy();
    }

    /** Records the transaction boundaries the interceptor asks for. */
    private static final class CountingTransactionManager implements PlatformTransactionManager {

        private int begun;
        private int committed;
        private int rolledBack;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            begun++;
            return new SimpleTransactionStatus(true);
        }

        @Override
        public void commit(TransactionStatus status) {
            committed++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rolledBack++;
        }
    }
}
