package org.tornotron.echno_backend.common.identity.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.identity.EchnoOrgId;
import org.tornotron.echno_backend.common.identity.ExternalUserId;
import org.tornotron.echno_backend.common.identity.IdentityCapabilities;
import org.tornotron.echno_backend.common.identity.IdentityProvider;
import org.tornotron.echno_backend.common.identity.OrgRoleGroups;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/** The queue on CockroachDB, against a provider that fails when told to. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({IdentityTaskQueue.class, IdentityTaskWorker.class})
class IdentityTaskQueueIT extends AbstractIntegrationTest {

    private static final EchnoOrgId ORGANIZATION = new EchnoOrgId(987_654L);

    @Autowired
    private EntityManager em;

    @Autowired
    private IdentityTaskQueue queue;

    @Autowired
    private IdentityTaskWorker worker;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private IdentityProvider provider;

    private final OrgRoleGroups roleGroups = org.mockito.Mockito.mock(OrgRoleGroups.class);

    private ExternalUserId account;

    @BeforeEach
    void anAccountAndAnEmptyQueue() {
        jdbc.update("DELETE FROM identity_task");
        account = new ExternalUserId("subject-" + UUID.randomUUID());
        when(provider.capabilities()).thenReturn(new IdentityCapabilities(Optional.empty(), Optional.of(roleGroups)));
    }

    @AfterEach
    void leaveTheQueueEmpty() {
        jdbc.update("DELETE FROM identity_task");
    }

    private void makeItDueAgain() {
        jdbc.update("UPDATE identity_task SET next_attempt_at = now() - INTERVAL '1 second' WHERE status = 'PENDING'");
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM identity_task", String.class);
    }

    @Test
    void aCallThatWorksRunsOnceAndIsFinished() {
        queue.removeMember(account, ORGANIZATION);

        worker.runDue();
        worker.runDue();

        verify(provider, times(1)).removeMember(account, ORGANIZATION);
        assertThat(status()).isEqualTo("DONE");
    }

    @Test
    void aCallThatFailsIsTriedAgainLaterAndRecordsWhy() {
        doThrow(new IllegalStateException("provider down")).when(provider).removeMember(any(), any());
        queue.removeMember(account, ORGANIZATION);

        worker.runDue();
        worker.runDue();

        verify(provider, times(1)).removeMember(account, ORGANIZATION);
        assertThat(status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT last_error FROM identity_task", String.class)).contains("provider down");
        assertThat(jdbc.queryForObject("SELECT next_attempt_at > now() FROM identity_task", Boolean.class)).isTrue();

        reset(provider);
        makeItDueAgain();
        worker.runDue();

        verify(provider, times(1)).removeMember(account, ORGANIZATION);
        assertThat(status()).isEqualTo("DONE");
    }

    @Test
    void aCallThatNeverWorksStopsAfterAWhile() {
        doThrow(new IllegalStateException("provider down")).when(provider).removeMember(any(), any());
        queue.removeMember(account, ORGANIZATION);
        jdbc.update("UPDATE identity_task SET attempts = ?", IdentityTaskWorker.MOST_ATTEMPTS - 1);

        worker.runDue();

        assertThat(status()).isEqualTo("FAILED");
    }

    @Test
    void theWaitGrowsAndStopsGrowing() {
        assertThat(IdentityTaskWorker.waitAfter(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(IdentityTaskWorker.waitAfter(2)).isEqualTo(Duration.ofMinutes(1));
        assertThat(IdentityTaskWorker.waitAfter(29)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void askingTwiceForTheSameThingIsOneCall() {
        queue.removeMember(account, ORGANIZATION);
        queue.removeMember(account, ORGANIZATION);

        worker.runDue();

        verify(provider, times(1)).removeMember(account, ORGANIZATION);
    }

    @Test
    void aRowDeliveredTwiceByTwoReplicasIsClaimedOnce() {
        queue.removeMember(account, ORGANIZATION);
        jdbc.update("UPDATE identity_task SET next_attempt_at = now() + INTERVAL '1 hour'");

        worker.runDue();

        verify(provider, never()).removeMember(any(), any());
    }

    @Test
    void theLastOfAGiveAndATakeIsWhatTheProviderHears() {
        queue.recordRole(account, ORGANIZATION, OrgRole.HR_ADMIN, true);
        queue.recordRole(account, ORGANIZATION, OrgRole.HR_ADMIN, false);

        worker.runDue();

        verify(roleGroups, never()).assign(any(), any(), any());
        verify(roleGroups, times(1)).remove(account, ORGANIZATION, OrgRole.HR_ADMIN);
    }

    @Test
    void differentRolesDoNotReplaceEachOther() {
        queue.recordRole(account, ORGANIZATION, OrgRole.HR_ADMIN, true);
        queue.recordRole(account, ORGANIZATION, OrgRole.STORE_KEEPER, true);

        worker.runDue();

        verify(roleGroups, times(1)).assign(account, ORGANIZATION, OrgRole.HR_ADMIN);
        verify(roleGroups, times(1)).assign(account, ORGANIZATION, OrgRole.STORE_KEEPER);
    }

    @Test
    void aProviderWithNoRoleGroupsFinishesARoleTaskWithNoCall() {
        when(provider.capabilities()).thenReturn(IdentityCapabilities.none());
        queue.recordRole(account, ORGANIZATION, OrgRole.HR_ADMIN, true);

        worker.runDue();

        assertThat(status()).isEqualTo("DONE");
    }

    @Test
    void aRolledBackTransactionAsksForNothing() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            queue.removeMember(account, ORGANIZATION);
            status.setRollbackOnly();
        });
        worker.runDue();

        verify(provider, never()).removeMember(any(), any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_task", Integer.class)).isZero();
    }

    @Test
    void sessionsEndOnlyWhenNoActiveEmployeeRemains() {
        Long[] ids = new Long[3];
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            AccessFixtures fixtures = new AccessFixtures(em);
            Organization org = fixtures.organization("Sessions " + UUID.randomUUID());
            Employee person = fixtures.employee(org, "sessions-" + UUID.randomUUID());
            ids[0] = org.getId();
            ids[1] = person.getId();
            ids[2] = person.getUser().getId();
        });
        try {
            queue.endSessionsIfNoActiveEmployee(account, ids[2]);
            worker.runDue();
            verify(provider, never()).endSessions(any());

            jdbc.update("UPDATE employee SET status = 'resigned' WHERE id = ?", ids[1]);
            queue.endSessionsIfNoActiveEmployee(account, ids[2]);
            worker.runDue();
            verify(provider, times(1)).endSessions(account);
        } finally {
            jdbc.update("DELETE FROM employee WHERE id = ?", ids[1]);
            jdbc.update("DELETE FROM users_table WHERE id = ?", ids[2]);
            jdbc.update("DELETE FROM organization WHERE id = ?", ids[0]);
        }
    }
}
