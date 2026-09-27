package org.tornotron.echno_backend.modules.toolboxtalks;

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
import org.tornotron.echno_backend.modules.toolboxtalks.api.ToolboxTalkMissingEvent;
import org.tornotron.echno_backend.modules.toolboxtalks.job.ToolboxTalkReminderJob;
import org.tornotron.echno_backend.modules.toolboxtalks.time.ToolboxTalksClockConfiguration;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * The reminder's per-organization pass, run against two organizations in a real database with
 * the production filter wiring: each organization is reminded about its own projects and nobody
 * else's.
 *
 * <p>Before #877 each pass ran its reads under the runner's plain entry, which pins the tenant
 * and opens no transaction. {@link HibernateFilterConfig} therefore never enabled the
 * {@code orgFilter}, the reads return projections the load listener never sees, and organization
 * A's pass published a missing-talk event for organization B's project, under B's project name.
 */
@DataJpaTest
@RecordApplicationEvents
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AopAutoConfiguration.class, HibernateFilterConfig.class, TenantIsolationListenerRegistrar.class,
        UnscopedAccessGuard.class, SimpleMeterRegistry.class, TransactionalWorkRunner.class,
        TenantScopedJobRunner.class, ToolboxTalksClockConfiguration.class, ToolboxTalkReminderJob.class})
// The job's runner opens transactions of its own, so no test-managed transaction may be outstanding.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ToolboxTalkReminderJobTenantIT extends AbstractIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 18);

    @Autowired
    private ToolboxTalkReminderJob job;

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
        TenantContext.declareUnscoped("ToolboxTalkReminderJobTenantIT seed");
        try {
            inCommittedTx(() -> {
                Organization orgA = persistOrganization("Reminder Tenant Org A");
                Organization orgB = persistOrganization("Reminder Tenant Org B");
                Project a = persistProject("Reminder Tower A", orgA);
                Project b = persistProject("Reminder Tower B", orgB);
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
        when(moduleRegistry.isEnabledForOrg(eq(ToolboxTalksModule.ID), eq(orgAId))).thenReturn(true);
        when(moduleRegistry.isEnabledForOrg(eq(ToolboxTalksModule.ID), eq(orgBId))).thenReturn(true);
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

        List<ToolboxTalkMissingEvent> missing = applicationEvents.stream(ToolboxTalkMissingEvent.class).toList();
        assertThat(reminded).isEqualTo(2);
        assertThat(missing).filteredOn(event -> event.organizationId().equals(orgAId))
                .extracting(ToolboxTalkMissingEvent::projectId)
                .containsExactly(projectAId);
        assertThat(missing).filteredOn(event -> event.organizationId().equals(orgBId))
                .extracting(ToolboxTalkMissingEvent::projectId)
                .containsExactly(projectBId);
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
