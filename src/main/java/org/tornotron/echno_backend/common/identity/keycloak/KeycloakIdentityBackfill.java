package org.tornotron.echno_backend.common.identity.keycloak;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.identity.ConditionalOnKeycloak;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.startup.StartupTask;

/**
 * Gives every existing user and organization its identity row under the Keycloak issuer, from what
 * {@code users_table.keycloak_id} and the organization's id already say. It asks Keycloak nothing:
 * an organization's group is named from its id, so this holds with Keycloak down.
 *
 * <p>Works in batches ordered by id, each its own transaction, and writes only what is missing, so a
 * rerun changes nothing and a run that stops part way is finished by the next.
 */
@Component
@ConditionalOnKeycloak
@Order(StartupTaskOrder.IDENTITY_BACKFILL)
@Slf4j
public class KeycloakIdentityBackfill implements StartupTask {

    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final TransactionRetryTemplate transactions;
    private final KeycloakTokenMapper mapper;

    public KeycloakIdentityBackfill(JdbcTemplate jdbc, TransactionRetryTemplate transactions,
                                    KeycloakTokenMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "keycloak-identity-backfill";
    }

    @Override
    public void run() {
        int users = backfillUsers();
        int organizations = backfillOrganizations();
        log.info("Identity backfill added {} user and {} organization rows", users, organizations);
    }

    private int backfillUsers() {
        int added = 0;
        long after = 0;
        while (true) {
            List<Long> batch = nextIds(
                    "SELECT id FROM users_table WHERE id > ? AND keycloak_id IS NOT NULL ORDER BY id LIMIT ?", after);
            if (batch.isEmpty()) {
                return added;
            }
            long from = batch.getFirst();
            long to = batch.getLast();
            added += transactions.execute("KeycloakIdentityBackfill.users", () -> jdbc.update("""
                    INSERT INTO user_identity (id, provider, issuer, subject, user_id)
                    SELECT gen_random_uuid(), ?, ?, u.keycloak_id, u.id
                    FROM users_table u
                    WHERE u.id BETWEEN ? AND ? AND u.keycloak_id IS NOT NULL
                    ON CONFLICT (issuer, subject) DO NOTHING
                    """, mapper.provider(), mapper.issuer(), from, to));
            after = to;
        }
    }

    private int backfillOrganizations() {
        int added = 0;
        long after = 0;
        while (true) {
            List<Long> batch = nextIds("SELECT id FROM organization WHERE id > ? ORDER BY id LIMIT ?", after);
            if (batch.isEmpty()) {
                return added;
            }
            long from = batch.getFirst();
            long to = batch.getLast();
            added += transactions.execute("KeycloakIdentityBackfill.organizations", () -> jdbc.update("""
                    INSERT INTO organization_identity (id, provider, provider_org_id, organization_id)
                    SELECT gen_random_uuid(), ?, CONCAT(CAST(? AS VARCHAR), CAST(o.id AS VARCHAR)), o.id
                    FROM organization o
                    WHERE o.id BETWEEN ? AND ?
                    ON CONFLICT DO NOTHING
                    """, mapper.provider(), KeycloakIdentityProvider.GROUP_PREFIX, from, to));
            after = to;
        }
    }

    private List<Long> nextIds(String sql, long after) {
        return jdbc.queryForList(sql, Long.class, after, BATCH_SIZE);
    }
}
