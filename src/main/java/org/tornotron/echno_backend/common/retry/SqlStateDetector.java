package org.tornotron.echno_backend.common.retry;

import java.sql.SQLException;

/**
 * Finds a given SQLSTATE anywhere in the exception a database failure arrived as.
 *
 * <p>By the time a driver error reaches application code Spring has wrapped it, typically
 * as a {@code DataAccessException} over a Hibernate exception over the driver's
 * {@link SQLException}, so the cause chain has to be walked. {@code SQLException} also
 * keeps its siblings on a second chain of its own
 * ({@link SQLException#getNextException()}), which the PostgreSQL driver uses for batch
 * failures, so that chain is followed too.
 *
 * <p>The test is always the SQLSTATE, never the message text: the wording the server puts
 * around a code varies between versions and carries transaction ids and node addresses,
 * so matching on it is brittle in exactly the situation where a false negative costs the
 * user their write.
 */
public final class SqlStateDetector {

    /** SQLSTATE class 40, code 001: serialization failure, the loser of a conflict. */
    public static final String SERIALIZATION_FAILURE = "40001";

    /** SQLSTATE class 23, code 505: a write violated a unique constraint or index. */
    public static final String UNIQUE_VIOLATION = "23505";

    /** Guards against a cause chain that loops back on itself. */
    private static final int MAX_CHAIN_DEPTH = 32;

    private SqlStateDetector() {
    }

    /**
     * Returns true when {@code throwable}, or anything it wraps, is a {@link SQLException}
     * reporting {@code sqlState}.
     */
    public static boolean carriesSqlState(Throwable throwable, String sqlState) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < MAX_CHAIN_DEPTH; depth++) {
            if (current instanceof SQLException sqlException && matches(sqlException, sqlState)) {
                return true;
            }
            Throwable cause = current.getCause();
            current = (cause == current) ? null : cause;
        }
        return false;
    }

    private static boolean matches(SQLException sqlException, String sqlState) {
        SQLException current = sqlException;
        for (int depth = 0; current != null && depth < MAX_CHAIN_DEPTH; depth++) {
            if (sqlState.equals(current.getSQLState())) {
                return true;
            }
            SQLException next = current.getNextException();
            current = (next == current) ? null : next;
        }
        return false;
    }
}
