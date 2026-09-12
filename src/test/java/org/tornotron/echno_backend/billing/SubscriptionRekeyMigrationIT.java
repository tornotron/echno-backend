package org.tornotron.echno_backend.billing;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.annotation.DirtiesContext;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rekey of {@code subscription} from the buying user to the organization (#643): that the
 * migration moves live rows to the buyer's default organization, that it expires what has lapsed,
 * and that the lookup the gate runs is keyed on the organization and honours the period end.
 *
 * <p>The migration half builds the billing tables in their pre-rekey shape on a fresh database
 * inside the shared CockroachDB container, plants rows the way staging holds them, and then
 * runs the real 095 and 096 changesets over them. That is the only way to exercise the data
 * movement: by the time the shared schema exists the column is already NOT NULL and the old
 * shape cannot be written.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SubscriptionRekeyMigrationIT extends AbstractIntegrationTest {

    private static final String PRE_REKEY = "db/changelog/rekey-643/pre-rekey.xml";
    private static final String POST_REKEY = "db/changelog/rekey-643/post-rekey.xml";
    private static final String MIGRATION_DB = "rekey_643";

    private static final Long ORG_A = 993_001L;
    private static final Long ORG_B = 993_002L;

    @Autowired private DataSource dataSource;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private TestEntityManager entityManager;

    @AfterEach
    void dropMigrationDatabase() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MIGRATION_DB + " CASCADE");
        }
    }

    @Test
    void migrationMovesEachRowToItsBuyersDefaultOrganizationAndExpiresWhatHasLapsed() throws Exception {
        String url;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + MIGRATION_DB);
            url = c.getMetaData().getURL().replaceFirst("/[A-Za-z_]+(\\?|$)", "/" + MIGRATION_DB + "$1");
        }

        try (Connection conn = DriverManager.getConnection(url, "root", "")) {
            Contexts contexts = new Contexts();
            new Liquibase(PRE_REKEY, new ClassLoaderResourceAccessor(), new JdbcConnection(conn))
                    .update(contexts, new LabelExpression());

            try (Statement s = conn.createStatement()) {
                // Two buyers, each with a default organization, the way staging looks.
                s.execute("INSERT INTO users_table (id, keycloak_id, name, default_organization_id) VALUES "
                        + "(1, 'kc-1', 'Buyer One', 2), "
                        + "(2, 'kc-2', 'Buyer Two', 1)");
                // The plan and feature come from the seed changeset that has already run; both
                // rows are on PRO, as the two on staging are. One lapsed in May and is still
                // TRIALING, the other is genuinely live.
                s.execute("INSERT INTO subscription (id, user_id, plan_id, status, current_period_start, current_period_end) VALUES "
                        + "(1, 1, (SELECT id FROM plan WHERE code = 'PRO'), 'TRIALING', '2026-05-01T00:00:00Z', '2026-05-31T00:00:00Z'), "
                        + "(2, 2, (SELECT id FROM plan WHERE code = 'PRO'), 'ACTIVE', now() - interval '1 day', now() + interval '29 days')");
                s.execute("INSERT INTO usage_record (id, user_id, subscription_id, feature_id, usage_amount, period_start, period_end) VALUES "
                        + "(1, 2, 2, (SELECT id FROM feature WHERE code = 'CREATE_ORGANIZATION'), 1, now(), now() + interval '30 days'), "
                        + "(2, 1, 1, (SELECT id FROM feature WHERE code = 'CREATE_ORGANIZATION'), 1, now(), now() + interval '30 days')");
            }

            new Liquibase(POST_REKEY, new ClassLoaderResourceAccessor(), new JdbcConnection(conn))
                    .update(contexts, new LabelExpression());

            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT id, organization_id, user_id, status FROM subscription ORDER BY id")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("id")).isEqualTo(1);
                assertThat(rs.getLong("organization_id")).isEqualTo(2);
                assertThat(rs.getLong("user_id")).isEqualTo(1);
                assertThat(rs.getString("status")).isEqualTo("EXPIRED");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("id")).isEqualTo(2);
                assertThat(rs.getLong("organization_id")).isEqualTo(1);
                assertThat(rs.getString("status")).isEqualTo("ACTIVE");
                assertThat(rs.next()).isFalse();
            }
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT id, organization_id FROM usage_record ORDER BY id")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("organization_id")).isEqualTo(1);
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("organization_id")).isEqualTo(2);
            }
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT is_nullable FROM information_schema.columns "
                         + "WHERE table_name = 'subscription' AND column_name = 'organization_id'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("NO");
            }
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM plan_feature pf "
                         + "JOIN feature f ON f.id = pf.feature_id WHERE f.code = 'MODULE_INSPECTIONS' AND pf.enabled")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).as("MODULE_INSPECTIONS granted on every seeded plan").isEqualTo(4);
            }
        }
    }

    @Test
    void activeLookupIsKeyedOnTheOrganizationAndHonoursThePeriodEnd() {
        Plan plan = entityManager.persist(Plan.builder().code("rekey-it-plan").name("Rekey IT").build());
        Instant now = Instant.now();

        entityManager.persist(Subscription.builder()
                .organizationId(ORG_A).userId(1L).plan(plan)
                .status(SubscriptionStatus.TRIALING)
                .currentPeriodStart(now.minus(90, ChronoUnit.DAYS))
                .currentPeriodEnd(now.minus(60, ChronoUnit.DAYS))
                .build());
        Subscription live = entityManager.persist(Subscription.builder()
                .organizationId(ORG_B).userId(1L).plan(plan)
                .status(SubscriptionStatus.ACTIVE)
                .currentPeriodStart(now)
                .currentPeriodEnd(now.plus(30, ChronoUnit.DAYS))
                .build());
        entityManager.flush();
        entityManager.clear();

        Optional<Subscription> lapsed = subscriptionRepository.findActiveSubscription(ORG_A);
        Optional<Subscription> current = subscriptionRepository.findActiveSubscription(ORG_B);

        assertThat(lapsed).as("a TRIALING row past its period end is not active").isEmpty();
        assertThat(current).map(Subscription::getId).contains(live.getId());
        assertThat(subscriptionRepository.findByOrganizationIdOrderByCreatedAtDesc(ORG_A)).hasSize(1);
    }
}
