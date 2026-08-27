package org.tornotron.echno_backend.common.retry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.tornotron.echno_backend.common.exception.TransactionRetriesExhaustedException;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Runs a unit of work in a transaction and starts it over when the database aborts it with a
 * serialization failure (SQLSTATE {@code 40001}).
 *
 * <p>Under {@code SERIALIZABLE} an abort is the database telling the client that its snapshot
 * no longer holds and the whole transaction has to run again. Retrying a statement inside the
 * aborted transaction achieves nothing: it is already doomed and every further statement on it
 * fails. The restart therefore has to happen outside the transaction boundary.
 *
 * <p>This is a template rather than an aspect on purpose. An {@code @Around} aspect would have
 * to be ordered ahead of Spring's transaction interceptor to sit outside it, and this
 * application pins that interceptor at {@code Ordered.HIGHEST_PRECEDENCE} in
 * {@code EchnoBackendApplication}, which is {@code Integer.MIN_VALUE}. No advice can be ordered
 * ahead of it, so an annotation-driven retry would have run inside the transaction and quietly
 * done nothing. Here the boundary is a method call: {@link TransactionalWorkRunner} opens and
 * closes the transaction within {@code runInTransaction}, and the loop below sits around that
 * call, so every attempt is a new transaction whatever the advisor ordering happens to be.
 *
 * <p>Only wrap work that is safe to run again from the start. Anything that has already changed
 * the world outside the database by the time the commit fails, such as an object uploaded to
 * storage, a mail sent or a payment charged, would be repeated by the retry.
 *
 * <p>Usage:
 * <pre>
 * public void markRead(Long roomId) {
 *     retryTemplate.executeWithoutResult("ChatService.markRead", () -&gt; { ... });
 * }
 * </pre>
 *
 * <p>The wrapped method must not itself be {@code @Transactional}: the transaction belongs to
 * the runner, one per attempt. Retries are counted per operation under
 * {@code echno.transaction.retry.attempts} and give-ups under
 * {@code echno.transaction.retry.exhausted}, so contention shows up on the dashboards before it
 * shows up in a support ticket.
 */
@Component
public class TransactionRetryTemplate {

    private static final Logger logger = LoggerFactory.getLogger(TransactionRetryTemplate.class);

    /** Counter for each restarted attempt, tagged with the operation that contended. */
    private static final String ATTEMPTS_METRIC = "echno.transaction.retry.attempts";

    /** Counter for each unit of work that used up its attempts and failed the request. */
    private static final String EXHAUSTED_METRIC = "echno.transaction.retry.exhausted";

    /** Caps the doubling so a misconfigured backoff cannot overflow the shift. */
    private static final int MAX_BACKOFF_DOUBLINGS = 20;

    private final TransactionalWorkRunner runner;
    private final MeterRegistry meterRegistry;
    private final int maxAttempts;
    private final long initialBackoffMillis;
    private final long maxBackoffMillis;

    public TransactionRetryTemplate(
            TransactionalWorkRunner runner,
            MeterRegistry meterRegistry,
            @Value("${echno.transaction.retry.max-attempts:4}") int maxAttempts,
            @Value("${echno.transaction.retry.initial-backoff-millis:25}") long initialBackoffMillis,
            @Value("${echno.transaction.retry.max-backoff-millis:250}") long maxBackoffMillis) {
        this.runner = runner;
        this.meterRegistry = meterRegistry;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoffMillis = Math.max(0L, initialBackoffMillis);
        this.maxBackoffMillis = Math.max(0L, maxBackoffMillis);
    }

    /**
     * Runs {@code work} in its own transaction, restarting it from the beginning on a
     * serialization failure. {@code operation} names the unit of work for the logs and the
     * retry metric; keep it a stable, low-cardinality label such as {@code ChatService.markRead}.
     *
     * @throws TransactionRetriesExhaustedException when every attempt was aborted
     */
    public <T> T execute(String operation, Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // A caller already owns the transaction, so there is no boundary here to restart:
            // an abort dooms their transaction, not one of ours. Run the work once and let the
            // failure travel out to whoever opened the transaction.
            return runner.runInTransaction(work);
        }

        Throwable lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return runner.runInTransaction(work);
            } catch (RuntimeException failure) {
                if (!SerializationFailureDetector.isSerializationFailure(failure)) {
                    throw failure;
                }
                lastFailure = failure;
                if (attempt == maxAttempts) {
                    break;
                }
                counter(ATTEMPTS_METRIC, "Transactions restarted after a database serialization failure",
                        operation).increment();
                logger.info("Serialization failure on {}, restarting the transaction (attempt {} of {})",
                        operation, attempt + 1, maxAttempts);
                if (!backOff(attempt)) {
                    throw failure;
                }
            }
        }

        counter(EXHAUSTED_METRIC, "Units of work abandoned after using up their serialization retries",
                operation).increment();
        logger.warn("Gave up on {} after {} serialization failures", operation, maxAttempts, lastFailure);
        throw new TransactionRetriesExhaustedException(
                "The operation kept colliding with a concurrent change and was abandoned after "
                        + maxAttempts + " attempts", lastFailure);
    }

    /** The no-result form of {@link #execute(String, Supplier)}. */
    public void executeWithoutResult(String operation, Runnable work) {
        execute(operation, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Sleeps for a jittered, exponentially growing interval before the next attempt. Full
     * jitter rather than a fixed pause: two transactions that abort against each other and then
     * wait the same length of time simply collide again.
     *
     * @return false when the wait was interrupted, in which case the caller should stop retrying
     */
    private boolean backOff(int attempt) {
        long ceiling = Math.min(maxBackoffMillis,
                initialBackoffMillis << Math.min(attempt - 1, MAX_BACKOFF_DOUBLINGS));
        if (ceiling <= 0L) {
            return true;
        }
        long pause = ThreadLocalRandom.current().nextLong(ceiling + 1);
        if (pause <= 0L) {
            return true;
        }
        try {
            Thread.sleep(pause);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Counter counter(String name, String description, String operation) {
        return Counter.builder(name)
                .description(description)
                .tag("operation", operation)
                .register(meterRegistry);
    }
}
