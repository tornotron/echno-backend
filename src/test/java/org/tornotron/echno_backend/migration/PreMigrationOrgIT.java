package org.tornotron.echno_backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.identity.keycloak.KeycloakIdentityBackfill;
import org.tornotron.echno_backend.common.identity.keycloak.KeycloakTokenMapper;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.common.startup.StartupTaskRunner;
import org.tornotron.echno_backend.common.startup.StartupTaskState;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * An organization and its people built the old way, with only {@code users_table.keycloak_id} and the
 * old tables to go on, then the startup tasks, as a staging's data meets them on its first boot. Each
 * check runs the tasks twice, because every boot runs them again.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({PreMigrationOrgIT.Wiring.class, StartupTaskRunner.class, StartupTaskState.class,
        KeycloakIdentityBackfill.class, KeycloakTokenMapper.class,
        TransactionRetryTemplate.class, TransactionalWorkRunner.class})
class PreMigrationOrgIT extends AbstractIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Wiring {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private EntityManager em;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StartupTaskRunner runner;

    @Autowired
    private StartupTaskState state;

    private TransactionTemplate transaction;
    private Long organization;
    private Long employee;
    private Long user;
    private String subject;

    @BeforeEach
    void anOrganizationBuiltTheOldWay() {
        transaction = new TransactionTemplate(transactionManager);
        String unique = UUID.randomUUID().toString().substring(0, 8);
        transaction.executeWithoutResult(status -> {
            AccessFixtures fixtures = new AccessFixtures(em);
            Organization org = fixtures.organization("Pre-migration " + unique);
            Employee person = fixtures.employee(org, "premigration-" + unique);
            organization = org.getId();
            employee = person.getId();
            user = person.getUser().getId();
            subject = person.getUser().getKeycloakId();
        });
    }

    @AfterEach
    void removeTheRows() {
        // The identity rows go with these through their foreign keys.
        jdbc.update("DELETE FROM employee WHERE id = ?", employee);
        jdbc.update("DELETE FROM users_table WHERE id = ?", user);
        jdbc.update("DELETE FROM organization WHERE id = ?", organization);
    }

    @Test
    void everyUserAndOrganizationGetsItsIdentityOnce() {
        assertThat(runner.runOnce()).isTrue();

        String linked = jdbc.queryForObject(
                "SELECT subject FROM user_identity WHERE user_id = ? AND provider = 'keycloak'", String.class, user);
        assertThat(linked).isEqualTo(subject);
        String group = jdbc.queryForObject(
                "SELECT provider_org_id FROM organization_identity WHERE organization_id = ? AND provider = 'keycloak'",
                String.class, organization);
        assertThat(group).isEqualTo("org-" + organization);
        assertThat(state.hasCompleted("keycloak-identity-backfill")).isTrue();

        Integer rows = identityRows();
        assertThat(runner.runOnce()).isTrue();
        assertThat(identityRows()).isEqualTo(rows);
    }

    @Test
    void aUserWithNoProviderAccountIsLeftOut() {
        jdbc.update("UPDATE users_table SET keycloak_id = NULL WHERE id = ?", user);

        assertThat(runner.runOnce()).isTrue();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_identity WHERE user_id = ?", Integer.class, user))
                .isZero();
    }

    private Integer identityRows() {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM user_identity WHERE user_id = ?)
                     + (SELECT count(*) FROM organization_identity WHERE organization_id = ?)
                """, Integer.class, user, organization);
    }
}
