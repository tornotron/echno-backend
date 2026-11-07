package org.tornotron.echno_backend.modules.access.matrix.service;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.startup.StartupTask;

/**
 * Reconciles every organization on every boot, each in its own transaction, so an organization is
 * either complete or untouched and a run that stops part way is finished by the next. It reconciles
 * rather than picking the organizations with no roles, so a change to the templates reaches them all.
 * Runs after the role import, which it reads the result of.
 */
@Component
@Order(AccessRoleBackfill.ORDER)
@Slf4j
public class AccessRoleBackfill implements StartupTask {

    public static final String NAME = "access-role-backfill";
    static final int ORDER = 400;

    private final JdbcTemplate jdbc;
    private final TransactionRetryTemplate transactions;
    private final AccessRoleReconciler reconciler;
    private final CachedAccessFactsSource facts;

    public AccessRoleBackfill(JdbcTemplate jdbc, TransactionRetryTemplate transactions,
                              AccessRoleReconciler reconciler, CachedAccessFactsSource facts) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.reconciler = reconciler;
        this.facts = facts;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run() {
        List<Long> organizations = jdbc.queryForList("SELECT id FROM organization ORDER BY id", Long.class);
        List<Long> failed = new ArrayList<>();
        for (Long organization : organizations) {
            try {
                transactions.executeWithoutResult(NAME, () -> reconciler.reconcile(organization));
            } catch (RuntimeException e) {
                failed.add(organization);
                log.error("Could not reconcile the roles of organization {}", organization, e);
            }
        }
        facts.evictAllAfterCommit();
        log.info("Reconciled the roles of {} organizations", organizations.size() - failed.size());
        if (!failed.isEmpty()) {
            throw new IllegalStateException("Roles not reconciled for organizations " + failed);
        }
    }
}
