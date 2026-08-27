package org.tornotron.echno_backend.common.retry;

import org.junit.jupiter.api.Test;
import org.hibernate.exception.LockAcquisitionException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SerializationFailureDetector}. The point of the class is that it reads
 * the SQLSTATE off the driver's exception rather than matching the server's wording, and that it
 * finds that exception however deeply the layers above have wrapped it, so those are what is
 * tested here: the real shape of a CockroachDB abort as it arrives from Spring, the driver's
 * second (next-exception) chain, the codes that must not be mistaken for a retryable abort, and
 * the chains that would otherwise make the walk loop forever.
 */
class SerializationFailureDetectorTest {

    /** The wording CockroachDB puts on a serializable abort, for a realistic message. */
    private static final String ABORT_MESSAGE =
            "restart transaction: TransactionRetryWithProtoRefreshError: TransactionRetryError: "
                    + "retry txn (RETRY_SERIALIZABLE - failed preemptive refresh)";

    @Test
    void findsTheSqlStateOnTheExceptionItself() {
        assertThat(SerializationFailureDetector.isSerializationFailure(
                new SQLException(ABORT_MESSAGE, "40001"))).isTrue();
    }

    @Test
    void findsTheSqlStateThroughTheWrappingSpringAndHibernateExceptions() {
        // The chain the live 500s arrived on: Spring's DataAccessException over Hibernate's
        // JDBCException over the driver's SQLException.
        SQLException driverFailure = new SQLException(ABORT_MESSAGE, "40001");
        Throwable wrapped = new CannotAcquireLockException(
                "Unable to commit against JDBC Connection",
                new LockAcquisitionException("could not execute statement", driverFailure));

        assertThat(SerializationFailureDetector.isSerializationFailure(wrapped)).isTrue();
    }

    @Test
    void findsTheSqlStateOnTheDriversNextExceptionChain() {
        // A batch failure reports the first statement's generic code and hangs the real one off
        // getNextException, which is a separate chain from getCause.
        SQLException batchFailure = new SQLException("batch entry failed", "XX000");
        batchFailure.setNextException(new SQLException(ABORT_MESSAGE, "40001"));

        assertThat(SerializationFailureDetector.isSerializationFailure(
                new CannotAcquireLockException("batch", batchFailure))).isTrue();
    }

    @Test
    void rejectsADifferentSqlState() {
        // 23505 is a unique-constraint violation: a real conflict the caller must not repeat.
        Throwable duplicateKey = new DataIntegrityViolationException(
                "duplicate key value", new SQLException("duplicate key value", "23505"));

        assertThat(SerializationFailureDetector.isSerializationFailure(duplicateKey)).isFalse();
    }

    @Test
    void rejectsAFailureThatCarriesNoSqlExceptionAtAll() {
        assertThat(SerializationFailureDetector.isSerializationFailure(
                new IllegalStateException("nothing to do with the database"))).isFalse();
        assertThat(SerializationFailureDetector.isSerializationFailure(null)).isFalse();
    }

    @Test
    void terminatesOnASelfReferencingChain() {
        SQLException selfReferencing = new SQLException("looping", "XX000");
        selfReferencing.setNextException(selfReferencing);

        assertThat(SerializationFailureDetector.isSerializationFailure(selfReferencing)).isFalse();
    }
}
