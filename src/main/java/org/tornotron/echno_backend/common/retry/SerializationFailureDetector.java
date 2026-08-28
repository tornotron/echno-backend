package org.tornotron.echno_backend.common.retry;

import java.sql.SQLException;

/**
 * Recognises the database's "you lost a serialization conflict, run it again" signal.
 *
 * <p>CockroachDB runs {@code SERIALIZABLE} and resolves a read-write conflict between two
 * concurrent transactions by aborting one of them with {@code RETRY_SERIALIZABLE}, expecting
 * the client to start it over. That is a normal outcome under contention rather than a fault,
 * and it is reported with SQLSTATE {@code 40001}.
 *
 * <p>The test here is the SQLSTATE, never the message text. The wording the server puts around
 * the code varies between versions and carries transaction ids and node addresses, so matching
 * on it is brittle in exactly the situation where a false negative costs the user their write.
 *
 * <p>Walking the wrapped exception to find that state is {@link SqlStateDetector}'s job, since
 * the same walk serves any SQLSTATE; see its javadoc for why both the cause chain and
 * {@link SQLException#getNextException()} have to be followed.
 */
public final class SerializationFailureDetector {

    /** SQLSTATE class 40, code 001: serialization failure. */
    public static final String SERIALIZATION_FAILURE_SQL_STATE = SqlStateDetector.SERIALIZATION_FAILURE;

    private SerializationFailureDetector() {
    }

    /**
     * Returns true when {@code throwable}, or anything it wraps, is a serialization failure the
     * caller may safely run again.
     */
    public static boolean isSerializationFailure(Throwable throwable) {
        return SqlStateDetector.carriesSqlState(throwable, SERIALIZATION_FAILURE_SQL_STATE);
    }
}
