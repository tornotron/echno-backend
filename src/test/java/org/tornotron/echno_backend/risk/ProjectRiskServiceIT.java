package org.tornotron.echno_backend.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;
import org.tornotron.echno_backend.common.configuration.JpaAuditingConfig;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.risk.dto.RiskDto;
import org.tornotron.echno_backend.risk.dto.RiskImportRequest;
import org.tornotron.echno_backend.risk.dto.RiskRequest;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * The risk register on the real migration: R-numbers per project, scores derived on the server,
 * the import and its dedupe, the stale-version refusal, and that one organization can neither read
 * nor change another's register.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProjectRiskService.class, TransactionRetryTemplate.class, TransactionalWorkRunner.class,
        JpaAuditingConfig.class, ProjectRiskServiceIT.Metrics.class})
class ProjectRiskServiceIT extends AbstractIntegrationTest {

    @TestConfiguration
    static class Metrics {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private ProjectRiskService service;

    @PersistenceContext
    private EntityManager entityManager;

    private Long orgAId;
    private Long orgBId;
    private Long projectAId;
    private Long projectA2Id;
    private Long projectBId;

    @BeforeEach
    void seed() {
        Organization orgA = persistOrganization("a");
        Organization orgB = persistOrganization("b");
        orgAId = orgA.getId();
        orgBId = orgB.getId();
        projectAId = persistProject(orgA, "Riverside Tower").getId();
        projectA2Id = persistProject(orgA, "Riverside Annexe").getId();
        projectBId = persistProject(orgB, "Hillside Tower").getId();
        becomeTenant(orgAId);
    }

    @AfterEach
    void clearTenant() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
        TenantContext.clear();
    }

    @Test
    void risksAreNumberedPerProjectAndScoredOnTheServer() {
        RiskDto first = service.create(projectAId, risk("Late drawings", "medium", "major"));
        RiskDto second = service.create(projectAId, risk("Crane breakdown", "very-high", "catastrophic"));
        RiskDto otherProject = service.create(projectA2Id, risk("Monsoon", "high", "moderate"));

        assertThat(first.riskId()).isEqualTo("R-001");
        assertThat(second.riskId()).isEqualTo("R-002");
        assertThat(otherProject.riskId()).isEqualTo("R-001");
        assertThat(first.riskScore()).isEqualTo(12);
        assertThat(second.riskScore()).isEqualTo(25);
        assertThat(first.residualScore()).isEqualTo(4);
        assertThat(first.subCategory()).isEqualTo("Incomplete or delayed design");
        assertThat(first.version()).isNotNull();
        assertThat(service.list(projectAId)).extracting(RiskDto::title)
                .containsExactly("Late drawings", "Crane breakdown");
    }

    @Test
    void aDeletedNumberIsNotReissuedWhileAHigherOneExists() {
        RiskDto first = service.create(projectAId, risk("One", "low", "minor"));
        service.create(projectAId, risk("Two", "low", "minor"));
        service.delete(projectAId, first.id());
        entityManager.flush();

        assertThat(service.create(projectAId, risk("Three", "low", "minor")).riskId()).isEqualTo("R-003");
        assertThat(service.list(projectAId)).extracting(RiskDto::riskId).containsExactly("R-002", "R-003");
    }

    @Test
    void anUpdateRescoresAndAStaleVersionIsRefused() {
        RiskDto created = service.create(projectAId, risk("Late drawings", "medium", "major"));

        RiskDto updated = service.update(projectAId, created.id(),
                withVersion(risk("Late drawings, podium", "high", "major"), created.version()));
        assertThat(updated.title()).isEqualTo("Late drawings, podium");
        assertThat(updated.riskScore()).isEqualTo(16);
        assertThat(updated.riskId()).isEqualTo("R-001");
        assertThat(updated.version()).isGreaterThan(created.version());

        assertThatThrownBy(() -> service.update(projectAId, created.id(),
                withVersion(risk("Someone else's edit", "low", "minor"), created.version())))
                .isInstanceOfSatisfying(ErrorResponseException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void anImportKeepsTheOrderAndSkipsWhatTheProjectAlreadyHolds() {
        service.create(projectAId, risk("Recorded here", "low", "minor"));
        List<RiskRequest> lines = List.of(
                imported(risk("From browser one", "high", "major"), "ref-1"),
                imported(risk("From browser two", "low", "negligible"), "ref-2"),
                imported(risk("Duplicate in the request", "low", "negligible"), "ref-2"),
                imported(legacy(risk("Old category", "medium", "moderate")), null));

        List<RiskDto> added = service.importRisks(projectAId, new RiskImportRequest(lines));
        assertThat(added).extracting(RiskDto::riskId).containsExactly("R-002", "R-003", "R-004");
        assertThat(added.get(2).category()).isEqualTo("schedule");
        entityManager.flush();

        List<RiskDto> again = service.importRisks(projectAId, new RiskImportRequest(List.of(
                imported(risk("From browser one", "high", "major"), "ref-1"))));
        assertThat(again).isEmpty();
        assertThat(service.list(projectAId)).hasSize(4);
    }

    @Test
    void anotherOrganizationCanNeitherReadNorChangeTheRegister() {
        RiskDto risk = service.create(projectAId, risk("Late drawings", "medium", "major"));
        entityManager.flush();
        assertThat(service.get(projectAId, risk.id()).title()).isEqualTo("Late drawings");

        // Every refusal below rolls back its participating transaction and marks the test's own as
        // rollback-only, so the calls that succeed come first.
        asTenant(orgBId, () -> {
            // Their own project of the same shape is untouched by A's register.
            assertThat(service.list(projectBId)).isEmpty();
            assertThatThrownBy(() -> service.list(projectAId)).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.get(projectAId, risk.id())).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.create(projectAId, risk("Intruder", "low", "minor")))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.update(projectAId, risk.id(), risk("Intruder", "low", "minor")))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.delete(projectAId, risk.id())).isInstanceOf(ResourceNotFoundException.class);
            return null;
        });
        // A risk id from one project does not resolve under another of the same tenant.
        assertThatThrownBy(() -> service.get(projectA2Id, risk.id())).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- helpers

    private static RiskRequest risk(String title, String probability, String impact) {
        return new RiskRequest(title, "  ", "design-engineering", "Incomplete or delayed design", "identified",
                "Ravi Kumar", probability, impact, "low", "minor", "mitigate", null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), null, new BigDecimal("250000.00"), 14,
                null, null);
    }

    private static RiskRequest withVersion(RiskRequest r, Long version) {
        return new RiskRequest(r.title(), r.description(), r.category(), r.subCategory(), r.status(), r.owner(),
                r.probability(), r.impact(), r.residualProbability(), r.residualImpact(), r.responseType(),
                r.contingencyPlan(), r.identifiedDate(), r.reviewDate(), r.closedDate(), r.costImpact(),
                r.scheduleImpact(), version, r.importRef());
    }

    private static RiskRequest imported(RiskRequest r, String ref) {
        return new RiskRequest(r.title(), r.description(), r.category(), r.subCategory(), r.status(), r.owner(),
                r.probability(), r.impact(), r.residualProbability(), r.residualImpact(), r.responseType(),
                r.contingencyPlan(), r.identifiedDate(), r.reviewDate(), r.closedDate(), r.costImpact(),
                r.scheduleImpact(), null, ref);
    }

    private static RiskRequest legacy(RiskRequest r) {
        return new RiskRequest(r.title(), r.description(), "schedule", null, r.status(), r.owner(),
                r.probability(), r.impact(), r.residualProbability(), r.residualImpact(), r.responseType(),
                r.contingencyPlan(), r.identifiedDate(), r.reviewDate(), r.closedDate(), r.costImpact(),
                r.scheduleImpact(), null, r.importRef());
    }

    private <T> T asTenant(Long orgId, Supplier<T> work) {
        Long previous = TenantContext.getCurrentOrgId();
        becomeTenant(orgId);
        try {
            return work.get();
        } finally {
            becomeTenant(previous);
        }
    }

    private void becomeTenant(Long orgId) {
        Session session = entityManager.unwrap(Session.class);
        session.disableFilter("orgFilter");
        TenantContext.setCurrentOrgId(orgId);
        session.enableFilter("orgFilter").setParameter("organizationId", orgId);
    }

    private Organization persistOrganization(String suffix) {
        Organization org = new Organization();
        org.setOrganizationName("Risk Register Org " + suffix);
        org.setOrganizationAddress("addr");
        org.setOrganizationEmail("risk-register-" + suffix + "@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);
        entityManager.flush();
        return org;
    }

    private Project persistProject(Organization org, String name) {
        Project project = new Project();
        project.setProjectName(name);
        project.setOrganization(org);
        entityManager.persist(project);
        entityManager.flush();
        return project;
    }
}
