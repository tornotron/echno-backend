package org.tornotron.echno_backend.modules.access.matrix.service;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.modules.access.matrix.repository.AccessFactsQueries;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectMembershipChanged;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * The cache against real commits and rollbacks on CockroachDB. A committed change is seen on the
 * next read, a rolled-back one evicts nothing, and a list of a hundred records runs the facts
 * queries once. These tests commit, so they run outside the slice's test transaction, and they
 * remove their rows afterwards, because every integration test shares one database and some of
 * them count what is in it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({AccessFactsQueries.class, DatabaseAccessFactsSource.class})
class AccessFactsEvictionIT extends AbstractIntegrationTest {

    private static final AccessPermission READ = AccessPermission.of("eviction:read");

    @Autowired
    private EntityManager em;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DatabaseAccessFactsSource database;

    private TransactionTemplate transaction;
    private CachedAccessFactsSource source;
    private MatrixAccessService engine;
    private Statistics statistics;

    private Long organization;
    private Long employee;
    private Long project;
    private Long user;
    private AccessSubject subject;
    private AccessRole role;

    @BeforeEach
    void aMemberWithARoleNotYetAssigned() {
        transaction = new TransactionTemplate(transactionManager);
        source = new CachedAccessFactsSource(database, new ConcurrentMapCacheManager());
        engine = new MatrixAccessService(source, area -> true);
        statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        String unique = UUID.randomUUID().toString().substring(0, 8);
        transaction.executeWithoutResult(status -> {
            AccessFixtures fixtures = new AccessFixtures(em);
            Organization org = fixtures.organization("Eviction " + unique);
            Employee person = fixtures.employee(org, "eviction-" + unique);
            Project tower = fixtures.project(org, "Eviction Tower " + unique);
            AccessRole holder = fixtures.newRole(org, "eviction-holder");
            holder.grant(READ, AccessScope.ALL);
            holder.grant(AccessPermission.of("eviction:edit"), AccessScope.ASSIGNED_PROJECTS);
            em.persist(holder);
            organization = org.getId();
            employee = person.getId();
            project = tower.getId();
            user = person.getUser().getId();
            subject = new AccessSubject(organization, employee, user);
            role = holder;
        });
    }

    @AfterEach
    void endTheRequestAndRemoveTheRows() {
        RequestContextHolder.resetRequestAttributes();
        statistics.setStatisticsEnabled(false);
        transaction.executeWithoutResult(status -> {
            // The access rows go with these through their foreign keys.
            delete("project", project);
            delete("employee", employee);
            delete("users_table", user);
            delete("organization", organization);
        });
    }

    private void delete(String table, Long id) {
        em.createNativeQuery("DELETE FROM " + table + " WHERE id = ?1").setParameter(1, id).executeUpdate();
    }

    private void assignTheRole() {
        Organization org = em.getReference(Organization.class, organization);
        Employee person = em.getReference(Employee.class, employee);
        new AccessFixtures(em).assign(org, person, em.getReference(AccessRole.class, role.getId()), null);
    }

    @Test
    void aCommittedChangeIsSeenOnTheNextRead() {
        assertThat(engine.check(subject, READ)).isFalse();

        transaction.executeWithoutResult(status -> {
            assignTheRole();
            source.evictAfterCommit(organization, employee);
        });

        assertThat(engine.check(subject, READ)).isTrue();
    }

    @Test
    void aRolledBackChangeEvictsNothing() {
        assertThat(engine.check(subject, READ)).isFalse();

        transaction.executeWithoutResult(status -> {
            assignTheRole();
            source.evictAfterCommit(organization, employee);
            status.setRollbackOnly();
        });
        statistics.clear();

        assertThat(engine.check(subject, READ)).isFalse();
        assertThat(statistics.getPrepareStatementCount()).as("only the membership read").isEqualTo(1);
    }

    @Test
    void joiningAProjectIsSeenOnTheNextRead() {
        transaction.executeWithoutResult(status -> {
            assignTheRole();
            source.evictAfterCommit(organization, employee);
        });
        AccessPermission edit = AccessPermission.of("eviction:edit");
        RecordFacts onTheProject = RecordFacts.of(organization, project);
        assertThat(engine.check(subject, edit, onTheProject)).isFalse();

        transaction.executeWithoutResult(status -> {
            em.find(Project.class, project).getEmployees().add(em.getReference(Employee.class, employee));
            new AccessFactsEvictionListener(source)
                    .onProjectMembershipChanged(new ProjectMembershipChanged(organization, employee));
        });

        assertThat(engine.check(subject, edit, onTheProject)).isTrue();
    }

    @Test
    void aListOfOneHundredRecordsRunsTheFactsQueriesOnce() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        statistics.clear();

        engine.check(subject, READ, RecordFacts.of(organization, project));
        long forOneRecord = statistics.getPrepareStatementCount();
        for (long record = 0; record < 100; record++) {
            engine.check(subject, READ, RecordFacts.of(organization, record));
        }

        assertThat(forOneRecord).as("the facts were loaded").isPositive();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(forOneRecord);
    }
}
