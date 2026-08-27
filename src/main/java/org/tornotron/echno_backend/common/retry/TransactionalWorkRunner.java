package org.tornotron.echno_backend.common.retry;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * Runs a block of work inside one transaction, and nothing else.
 *
 * <p>This exists as a bean of its own so that {@link TransactionRetryTemplate} can open a
 * transaction by calling through a proxy rather than by holding a {@code TransactionTemplate}.
 * Two things depend on the transaction being declarative here:
 *
 * <ul>
 *   <li>the transaction begins and ends entirely within a single call to
 *       {@link #runInTransaction(Supplier)}, so a retry loop wrapped around that call is
 *       outside the transaction boundary by construction;</li>
 *   <li>{@code HibernateFilterConfig} advises {@code @Transactional} methods in this package
 *       tree to enable the {@code orgFilter} tenant filter on the session. A programmatic
 *       {@code TransactionTemplate} carries no such annotation, so a transaction opened that
 *       way would run with tenant filtering off.</li>
 * </ul>
 *
 * <p>Propagation is the default {@code REQUIRED}: if the caller already owns a transaction the
 * work joins it, and {@link TransactionRetryTemplate} declines to retry in that case because
 * there would be no boundary here to restart.
 */
@Component
public class TransactionalWorkRunner {

    @Transactional
    public <T> T runInTransaction(Supplier<T> work) {
        return work.get();
    }
}
