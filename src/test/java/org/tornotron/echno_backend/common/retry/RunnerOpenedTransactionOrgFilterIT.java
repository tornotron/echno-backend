package org.tornotron.echno_backend.common.retry;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.multitenancy.HibernateFilterConfig;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantIsolationListenerRegistrar;
import org.tornotron.echno_backend.common.multitenancy.UnscopedAccessGuard;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * Whether a transaction opened by {@link TransactionalWorkRunner} runs under the Hibernate
 * {@code orgFilter}, measured against a real database with the production wiring in place:
 * {@link HibernateFilterConfig} woven around {@code @Transactional}, the transaction interceptor
 * at {@code HIGHEST_PRECEDENCE} (the slice takes {@code EchnoBackendApplication} as its
 * configuration, so its {@code @EnableTransactionManagement} order applies here too), and the
 * fail-closed load listener behind both.
 *
 * <p>The question came out of #814. A split through the runner in {@code ObservationIntakeIT} read
 * another organization's row, and the reading at the time was that the aspect had enabled the
 * filter on the outer session rather than on the one the runner opened. The slice that test ran in
 * did not import the aspect at all, though: the only filter it ever had was the one the test enabled
 * by hand on its own session, and a filter is session state, so a fresh session opened after that
 * one was suspended started with nothing. That was the slice's arrangement, not the application's
 * (the slice has since been given the application's wiring).
 *
 * <p>In the application the interceptor opens the transaction first, binding the new session to
 * the thread, and only then does the aspect run and unwrap the bound session, so the session it
 * enables the filter on is the runner's own. These tests pin that for every shape the runner is
 * used in: a fresh transaction with none outstanding, one attempt of the retry template, a
 * transaction joined inside an outer one, one joined after the outer one was suspended, and the
 * two-transaction split from #814 itself. The bypass and unscoped declarations are pinned beside
 * them so a fix to the filter path cannot quietly widen or narrow either.
 *
 * <p>Run with {@link HibernateFilterConfig} removed from the imports, every tenant-scoped case
 * here fails: the filter is gone and the load listener refuses the foreign row instead. That is
 * the failure {@code ObservationIntakeIT} saw, minus the listener it also did not import then.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AopAutoConfiguration.class, HibernateFilterConfig.class, TenantIsolationListenerRegistrar.class, UnscopedAccessGuard.class,
        SimpleMeterRegistry.class, TransactionalWorkRunner.class, TransactionRetryTemplate.class})
@TestPropertySource(properties = {
        "echno.transaction.retry.initial-backoff-millis=0",
        "echno.transaction.retry.max-backoff-millis=0"
})
// The runner has to open transactions of its own, so no test-managed transaction may be
// outstanding; the cases that need an outer one open it themselves.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RunnerOpenedTransactionOrgFilterIT extends AbstractIntegrationTest {

    @Autowired
    private TransactionalWorkRunner runner;

    @Autowired
    private TransactionRetryTemplate retryTemplate;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager entityManager;

    private Long orgAId;
    private Long orgBId;
    private Long projectAId;
    private Long projectBId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        TenantContext.declareUnscoped("RunnerOpenedTransactionOrgFilterIT seed");
        try {
            inCommittedTx(() -> {
                Organization orgA = persistOrganization("Runner Filter Org A");
                Organization orgB = persistOrganization("Runner Filter Org B");
                Project a = persistProject("Tower A", orgA);
                Project b = persistProject("Tower B", orgB);
                entityManager.flush();
                orgAId = orgA.getId();
                orgBId = orgB.getId();
                projectAId = a.getId();
                projectBId = b.getId();
            });
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void removeCommittedRows() {
        TenantContext.clear();
        if (orgAId == null) {
            return;
        }
        TenantContext.setBypass(true);
        try {
            inCommittedTx(() -> {
                entityManager.createNativeQuery("DELETE FROM project WHERE organization_id IN (:a,:b)")
                        .setParameter("a", orgAId).setParameter("b", orgBId).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM organization WHERE id IN (:a,:b)")
                        .setParameter("a", orgAId).setParameter("b", orgBId).executeUpdate();
            });
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aFreshTransactionOpenedByTheRunnerSeesOnlyTheCurrentTenant() {
        TenantContext.setCurrentOrgId(orgAId);

        List<Long> seen = runner.runInTransaction(this::projectIds);

        assertThat(seen).contains(projectAId).doesNotContain(projectBId);
    }

    @Test
    void anAttemptOfTheRetryTemplateSeesOnlyTheCurrentTenant() {
        TenantContext.setCurrentOrgId(orgAId);

        List<Long> seen = retryTemplate.execute("RunnerOpenedTransactionOrgFilterIT.list", this::projectIds);

        assertThat(seen).contains(projectAId).doesNotContain(projectBId);
    }

    @Test
    void aRunnerTransactionJoinedInsideAnOuterOneSeesOnlyTheCurrentTenant() {
        TenantContext.setCurrentOrgId(orgAId);

        // The outer transaction is programmatic, so no aspect ran for it and its session starts
        // with no filter. The runner joins it and the aspect enables the filter there.
        List<Long> seen = inTx(TransactionDefinition.PROPAGATION_REQUIRED,
                () -> runner.runInTransaction(this::projectIds));

        assertThat(seen).contains(projectAId).doesNotContain(projectBId);
    }

    @Test
    void aRunnerTransactionOpenedAfterTheOuterOneWasSuspendedSeesOnlyTheCurrentTenant() {
        TenantContext.setCurrentOrgId(orgAId);

        // The shape #814 described: an outer session that already carries the filter, suspended
        // while a new session is opened underneath it. The filter on the outer session is session
        // state and does not follow; what puts it on the new session is the aspect running on the
        // runner's own boundary, after the interceptor bound that session.
        List<Long> seen = inTx(TransactionDefinition.PROPAGATION_REQUIRED, () -> {
            entityManager.unwrap(Session.class).enableFilter("orgFilter").setParameter("organizationId", orgAId);
            return inTx(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                    () -> runner.runInTransaction(this::projectIds));
        });

        assertThat(seen).contains(projectAId).doesNotContain(projectBId);
    }

    @Test
    void theSecondTransactionOfASplitSeesOnlyTheCurrentTenant() {
        TenantContext.setCurrentOrgId(orgAId);

        // The insert-then-re-read split #814 item 2 wants: two runner transactions back to back,
        // the second reading what the first committed and nothing of another organization's.
        Long inserted = runner.runInTransaction(() -> {
            Project p = persistProject("Tower A2", entityManager.getReference(Organization.class, orgAId));
            entityManager.flush();
            return p.getId();
        });
        List<Long> seen = runner.runInTransaction(this::projectIds);

        assertThat(seen).contains(projectAId, inserted).doesNotContain(projectBId);
    }

    @Test
    void aBypassedTransactionOpenedByTheRunnerReadsAcrossTenants() {
        TenantContext.setCurrentOrgId(orgAId);
        TenantContext.setBypass(true);

        List<Long> seen = runner.runInTransaction(this::projectIds);

        assertThat(seen).contains(projectAId, projectBId);
    }

    @Test
    void anUnscopedTransactionOpenedByTheRunnerReadsAcrossTenants() {
        TenantContext.declareUnscoped("RunnerOpenedTransactionOrgFilterIT unscoped case");

        List<Long> seen = runner.runInTransaction(this::projectIds);

        assertThat(seen).contains(projectAId, projectBId);
    }

    @Test
    void anUnscopedDeclarationDoesNotWeakenAnActiveTenantInTheRunnersTransaction() {
        TenantContext.setCurrentOrgId(orgAId);
        TenantContext.declareUnscoped("RunnerOpenedTransactionOrgFilterIT unscoped beside a tenant");

        List<Long> seen = runner.runInTransaction(this::projectIds);

        assertThat(seen).contains(projectAId).doesNotContain(projectBId);
    }

    // ---------------------------------------------------------- helpers

    private List<Long> projectIds() {
        return projectRepository.findAll().stream().map(Project::getId).toList();
    }

    private <T> T inTx(int propagation, java.util.function.Supplier<T> work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(propagation);
        return tt.execute(status -> work.get());
    }

    private void inCommittedTx(Runnable work) {
        inTx(TransactionDefinition.PROPAGATION_REQUIRES_NEW, () -> {
            work.run();
            return null;
        });
    }

    private Project persistProject(String name, Organization organization) {
        Project project = new Project();
        project.setProjectName(name);
        project.setOrganization(organization);
        entityManager.persist(project);
        return project;
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);
        return org;
    }
}
