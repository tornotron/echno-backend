package org.tornotron.echno_backend.modules.access.matrix.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.startup.StartupTask;
import org.tornotron.echno_backend.modules.access.matrix.service.AccessReconcileReport.MemberDifference;

/**
 * Reconciles every organization on every boot, each in its own transaction, so an organization is
 * either complete or untouched and a run that stops part way is finished by the next. It reconciles
 * rather than picking the organizations with no roles, so a change to the templates reaches them all.
 * Runs after the role import, which it reads the result of.
 *
 * <p>After reconciling an organization it reads {@link AccessReconcileReport} in the same transaction
 * and logs, as a warning, every member whose matrix access differs from today's. With
 * {@code echno.access.backfill.dry-run=true} each organization's transaction is rolled back after the
 * report instead, so a copy of a database shows what the first boot would do without keeping it; it
 * also lists the organizations that have no roles yet, which a converted area would refuse until a
 * real run.
 */
@Component
@Order(AccessRoleBackfill.ORDER)
@Slf4j
public class AccessRoleBackfill implements StartupTask {

    public static final String NAME = "access-role-backfill";
    static final int ORDER = 400;

    /** What one run found: per organization, the members whose access differs, and those that failed. */
    public record Result(Map<Long, List<MemberDifference>> differences, List<Long> withoutRoles, List<Long> failed) {

        public boolean clean() {
            return failed.isEmpty() && differences.values().stream().allMatch(List::isEmpty);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionRetryTemplate transactions;
    private final TransactionTemplate rolledBack;
    private final AccessRoleReconciler reconciler;
    private final AccessReconcileReport report;
    private final CachedAccessFactsSource facts;
    private final boolean dryRun;

    public AccessRoleBackfill(JdbcTemplate jdbc, TransactionRetryTemplate transactions,
                              PlatformTransactionManager transactionManager, AccessRoleReconciler reconciler,
                              AccessReconcileReport report, CachedAccessFactsSource facts,
                              @Value("${echno.access.backfill.dry-run:false}") boolean dryRun) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.rolledBack = new TransactionTemplate(transactionManager);
        this.reconciler = reconciler;
        this.report = report;
        this.facts = facts;
        this.dryRun = dryRun;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run() {
        Result result = reconcileAll(dryRun);
        String mode = dryRun ? "Dry run, nothing kept: " : "";
        if (!result.withoutRoles().isEmpty()) {
            log.info("{}organizations with no roles before this run: {}", mode, result.withoutRoles());
        }
        int differing = 0;
        for (Map.Entry<Long, List<MemberDifference>> organization : result.differences().entrySet()) {
            for (MemberDifference member : organization.getValue()) {
                differing++;
                log.warn("{}employee {} of organization {} differs from today's access: missing {}, extra {}",
                        mode, member.employeeId(), organization.getKey(), member.missing(), member.extra());
            }
        }
        log.info("{}reconciled the roles of {} organizations; {} members differ from today's access",
                mode, result.differences().size(), differing);
        if (!result.failed().isEmpty()) {
            throw new IllegalStateException("Roles not reconciled for organizations " + result.failed());
        }
    }

    /**
     * Reconciles every organization and reports on each. With {@code dryRun} every organization's
     * transaction is rolled back after its report, so nothing is kept.
     */
    public Result reconcileAll(boolean dryRun) {
        return reconcile(jdbc.queryForList("SELECT id FROM organization ORDER BY id", Long.class), dryRun);
    }

    /** {@link #reconcileAll} for the named organizations only. */
    public Result reconcile(List<Long> organizations, boolean dryRun) {
        List<Long> withoutRoles = new ArrayList<>();
        for (Long organization : organizations) {
            if (Boolean.FALSE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM access_role WHERE organization_id = ?)", Boolean.class, organization))) {
                withoutRoles.add(organization);
            }
        }
        Map<Long, List<MemberDifference>> differences = new LinkedHashMap<>();
        List<Long> failed = new ArrayList<>();
        for (Long organization : organizations) {
            try {
                List<MemberDifference> found = dryRun
                        ? rolledBack.execute(status -> {
                            status.setRollbackOnly();
                            return reconcileAndReport(organization);
                        })
                        : transactions.execute(NAME, () -> reconcileAndReport(organization));
                differences.put(organization, found);
            } catch (RuntimeException e) {
                failed.add(organization);
                log.error("Could not reconcile the roles of organization {}", organization, e);
            }
        }
        if (!dryRun) {
            facts.evictAllAfterCommit();
        }
        return new Result(differences, withoutRoles, failed);
    }

    private List<MemberDifference> reconcileAndReport(Long organization) {
        reconciler.reconcile(organization);
        return report.differences(organization);
    }
}
