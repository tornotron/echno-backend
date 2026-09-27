package org.tornotron.echno_backend.common.multitenancy;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * What {@link TenantScopedJobRunner} leaves a repository read to see, with two organizations in a
 * real database and the production filter wiring in place: {@link HibernateFilterConfig} woven
 * around {@code @Transactional}, the fail-closed load listener behind it (the same slice as
 * {@code RunnerOpenedTransactionOrgFilterIT}).
 *
 * <p>The read is a scalar projection on purpose. That is the shape of #877: the toolbox-talk
 * reminder read project ids and names, which never pass the load listener, so the only thing
 * standing between one organization and another's rows was the {@code orgFilter}, and the plain
 * entry point never turned it on.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AopAutoConfiguration.class, HibernateFilterConfig.class, TenantIsolationListenerRegistrar.class,
        UnscopedAccessGuard.class, SimpleMeterRegistry.class, TransactionalWorkRunner.class,
        TenantScopedJobRunner.class})
// The runner has to open transactions of its own, so no test-managed transaction may be
// outstanding; the case that needs an outer one opens it itself.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TenantScopedJobRunnerOrgFilterIT extends AbstractIntegrationTest {

    @Autowired
    private TenantScopedJobRunner runner;

    @Autowired
    private TransactionalWorkRunner transactions;

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
        TenantContext.declareUnscoped("TenantScopedJobRunnerOrgFilterIT seed");
        try {
            inTx(TransactionDefinition.PROPAGATION_REQUIRES_NEW, () -> {
                Organization orgA = persistOrganization("Job Runner Filter Org A");
                Organization orgB = persistOrganization("Job Runner Filter Org B");
                Project a = persistProject("Depot A", orgA);
                Project b = persistProject("Depot B", orgB);
                entityManager.flush();
                orgAId = orgA.getId();
                orgBId = orgB.getId();
                projectAId = a.getId();
                projectBId = b.getId();
                return null;
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
            inTx(TransactionDefinition.PROPAGATION_REQUIRES_NEW, () -> {
                entityManager.createNativeQuery("DELETE FROM project WHERE organization_id IN (:a,:b)")
                        .setParameter("a", orgAId).setParameter("b", orgBId).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM organization WHERE id IN (:a,:b)")
                        .setParameter("a", orgAId).setParameter("b", orgBId).executeUpdate();
                return null;
            });
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void theTransactionalEntrySeesOnlyThePinnedOrganization() {
        List<Long> seen = runner.callForTenantInTransaction(orgAId, this::projectIds);

        assertThat(seen).contains(projectAId).doesNotContain(projectBId);
    }

    @Test
    void theTransactionalEntryJoiningATransactionOpenedBeforeTheTenantSeesOnlyThePinnedOrganization() {
        // The EntitlementProjection shape: a REQUIRES_NEW transaction opened before the tenant is
        // pinned, so nothing filtered its session on the way in. Joining it after pinning is what
        // puts the filter on.
        List<Long> seen = inTx(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                () -> runner.callForTenantInTransaction(orgAId, this::projectIds));

        assertThat(seen).contains(projectAId).doesNotContain(projectBId);
    }

    @Test
    void thePlainEntryLeavesARepositoryReadUnfiltered() {
        // Pins why the plain entry is only for work that draws its own transaction boundaries, and
        // why TenantJobTransactionBoundaryTest exists: the tenant is set, and still nothing
        // filtered the session this read ran on. This is the #877 leak, kept as a contract so
        // nobody mistakes the plain entry for a safe place to read.
        List<Long> seen = runner.callForTenant(orgAId, this::projectIds);

        assertThat(seen).contains(projectAId, projectBId);
    }

    @Test
    void thePlainEntryIsFilteredOnceItsWorkOpensATransactionOfItsOwn() {
        // What every compliant caller of the plain entry does: its reads go through a boundary.
        List<Long> seen = runner.callForTenant(orgAId, () -> transactions.runInTransaction(this::projectIds));

        assertThat(seen).contains(projectAId).doesNotContain(projectBId);
    }

    // ---------------------------------------------------------- helpers

    private List<Long> projectIds() {
        return entityManager.createQuery("SELECT p.id FROM Project p", Long.class).getResultList();
    }

    private <T> T inTx(int propagation, java.util.function.Supplier<T> work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(propagation);
        return tt.execute(status -> work.get());
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
