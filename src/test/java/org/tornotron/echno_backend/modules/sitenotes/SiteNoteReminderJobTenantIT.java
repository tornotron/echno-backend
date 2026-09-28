package org.tornotron.echno_backend.modules.sitenotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.common.multitenancy.HibernateFilterConfig;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantIsolationListenerRegistrar;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.multitenancy.UnscopedAccessGuard;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.modules.sitenotes.api.SiteNoteMissingEvent;
import org.tornotron.echno_backend.modules.sitenotes.job.SiteNoteReminderJob;
import org.tornotron.echno_backend.modules.sitenotes.time.SiteNotesClockConfiguration;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * The reminder's per-organization pass, run against two organizations in a real database with
 * the production filter wiring: each organization is reminded about its own projects and nobody
 * else's.
 *
 * <p>Before this test existed, the pass ran its reads under the runner's plain entry, which pins
 * the tenant and opens no transaction. {@link HibernateFilterConfig} therefore never enabled the
 * {@code orgFilter}, the reads return projections the load listener never sees, and organization
 * A's pass published a missing-note event for organization B's project, under B's project name
 * (the same bug Toolbox Talks had as #877, found again here on the lab box in Stage 0 review).
 */
@DataJpaTest
@RecordApplicationEvents
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AopAutoConfiguration.class, HibernateFilterConfig.class, TenantIsolationListenerRegistrar.class,
        UnscopedAccessGuard.class, SimpleMeterRegistry.class, TransactionalWorkRunner.class,
        TenantScopedJobRunner.class, SiteNotesClockConfiguration.class, SiteNoteReminderJob.class})
// The job's runner opens transactions of its own, so no test-managed transaction may be outstanding.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SiteNoteReminderJobTenantIT extends AbstractIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 23);

    @Autowired
    private SiteNoteReminderJob job;

    @MockitoBean
    private ModuleRegistry moduleRegistry;

    @Autowired
    private ApplicationEvents applicationEvents;

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
        TenantContext.declareUnscoped("SiteNoteReminderJobTenantIT seed");
        try {
            inCommittedTx(() -> {
                Organization orgA = persistOrganization("Reminder Tenant Org A");
                Organization orgB = persistOrganization("Reminder Tenant Org B");
                Project a = persistProject("Reminder Site A", orgA);
                Project b = persistProject("Reminder Site B", orgB);
                entityManager.flush();
                orgAId = orgA.getId();
                orgBId = orgB.getId();
                projectAId = a.getId();
                projectBId = b.getId();
            });
        } finally {
            TenantContext.clear();
        }
        // Only these two are entitled, so any other organization another test left behind is
        // skipped before a tenant is pinned.
        when(moduleRegistry.isEnabledForOrg(eq(SiteNotesModule.ID), eq(orgAId))).thenReturn(true);
        when(moduleRegistry.isEnabledForOrg(eq(SiteNotesModule.ID), eq(orgBId))).thenReturn(true);
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
    void eachOrganizationIsRemindedAboutItsOwnProjectsOnly() {
        int reminded = job.runPass(DAY);

        List<SiteNoteMissingEvent> missing = applicationEvents.stream(SiteNoteMissingEvent.class).toList();
        assertThat(reminded).isEqualTo(2);
        assertThat(missing).filteredOn(event -> event.organizationId().equals(orgAId))
                .extracting(SiteNoteMissingEvent::projectId)
                .containsExactly(projectAId);
        assertThat(missing).filteredOn(event -> event.organizationId().equals(orgBId))
                .extracting(SiteNoteMissingEvent::projectId)
                .containsExactly(projectBId);
        // The bug this test is named for: org A's pass must never name org B's project, or the
        // other way around.
        assertThat(missing).filteredOn(event -> event.organizationId().equals(orgAId))
                .extracting(SiteNoteMissingEvent::projectId)
                .doesNotContain(projectBId);
        assertThat(missing).filteredOn(event -> event.organizationId().equals(orgBId))
                .extracting(SiteNoteMissingEvent::projectId)
                .doesNotContain(projectAId);
    }

    // ---------------------------------------------------------- helpers

    private void inCommittedTx(Runnable work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> work.run());
    }

    private Project persistProject(String name, Organization organization) {
        Project project = new Project();
        project.setProjectName(name);
        project.setOrganization(organization);
        project.setStatus(ProjectCreationStatus.open);
        entityManager.persist(project);
        return project;
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        org.setIsActive(true);
        entityManager.persist(org);
        return org;
    }
}
