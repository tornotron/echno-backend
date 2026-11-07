package org.tornotron.echno_backend.employee.startup;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.startup.StartupTask;

/**
 * Sets a null {@code employee.status} to {@code active}, before anything starts reading that field to
 * decide access. Today nothing does, so a null has always worked as active, and older rows have it.
 * Null counts as holding nothing once the field decides, so without this they would lose their access.
 *
 * <p>Batches by id, each its own transaction. A rerun finds nothing to do.
 */
@Component
@Order(NullEmployeeStatusCleanup.ORDER)
@Slf4j
public class NullEmployeeStatusCleanup implements StartupTask {

    public static final String NAME = "null-employee-status-cleanup";
    static final int ORDER = 200;
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final TransactionRetryTemplate transactions;

    public NullEmployeeStatusCleanup(JdbcTemplate jdbc, TransactionRetryTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run() {
        int changed = 0;
        long after = 0;
        while (true) {
            List<Long> batch = jdbc.queryForList(
                    "SELECT id FROM employee WHERE status IS NULL AND id > ? ORDER BY id LIMIT ?",
                    Long.class, after, BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            long from = batch.getFirst();
            long to = batch.getLast();
            changed += transactions.execute("NullEmployeeStatusCleanup", () -> jdbc.update(
                    "UPDATE employee SET status = 'active' WHERE status IS NULL AND id BETWEEN ? AND ?", from, to));
            after = to;
        }
        if (changed > 0) {
            log.info("Set {} employees with no status to active", changed);
        }
    }
}
