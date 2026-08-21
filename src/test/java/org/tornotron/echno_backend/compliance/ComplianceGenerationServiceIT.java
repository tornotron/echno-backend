package org.tornotron.echno_backend.compliance;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.compliance.ai.ClaudeComplianceService;
import org.tornotron.echno_backend.compliance.ai.ComplianceSuggestion;
import org.tornotron.echno_backend.inspection.ComplianceRiskLevel;
import org.tornotron.echno_backend.inspection.InspectionOrigin;
import org.tornotron.echno_backend.inspection.InspectionStatus;
import org.tornotron.echno_backend.inspection.InspectionType;
import org.tornotron.echno_backend.inspection.dtos.InspectionDto;
import org.tornotron.echno_backend.inspection.mapper.InspectionMapperImpl;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;
import org.tornotron.echno_backend.project.enums.ProjectType;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Exercises compliance generation against a real CockroachDB with the migration-seeded
 * {@code compliance_rules} in place. The AI call is stubbed (never the real API): the
 * stub marks two of the seeded Tamil Nadu residential rules as applicable, and the test
 * asserts that exactly those become suggested, AI-generated compliance inspections in
 * lifecycle-phase order, and that a second run adds nothing (idempotent on the
 * (projectId, complianceRuleRef) pair).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ComplianceGenerationService.class, InspectionMapperImpl.class,
        TenantEntityHelper.class, EntryNumberGenerator.class})
class ComplianceGenerationServiceIT extends AbstractIntegrationTest {

    private static final String RULE_PRE = "TN-BPA";        // pre-construction
    private static final String RULE_POST = "TN-OCC-CERT";  // post-construction

    @Autowired
    private ComplianceGenerationService service;

    @MockBean
    private ClaudeComplianceService claudeComplianceService;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgAId;
    private Long projectId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization orgA = persistOrganization("Org Compliance A");

            Project project = new Project();
            project.setProjectName("Chennai Residency");
            project.setProjectAddress("12 Mount Road, Chennai, Tamil Nadu, India");
            project.setProjectType(ProjectType.RESIDENTIAL);
            project.setStatus(ProjectCreationStatus.approved);
            project.setOrganization(orgA);
            entityManager.persist(project);

            entityManager.flush();
            orgAId = orgA.getId();
            projectId = project.getId();
        });

        // The stub applies two of the six seeded TN residential rules and rejects the rest.
        when(claudeComplianceService.suggestCompliances(any(Project.class), anyString(), any()))
                .thenReturn(List.of(
                        new ComplianceSuggestion(RULE_POST, true, "critical",
                                List.of("Apply for the occupancy certificate"), "Required before handover",
                                "post-construction"),
                        new ComplianceSuggestion(RULE_PRE, true, "critical",
                                List.of("Obtain the building plan approval"), "Required before work starts",
                                "pre-construction"),
                        new ComplianceSuggestion("TN-FIRE-NOC", false, null, null,
                                "Not required for this low-rise residential project", "pre-construction")
                ));

        TenantContext.setCurrentOrgId(orgAId);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        if (orgAId == null) {
            return;
        }
        // Committed seed rows survive the rollback; remove them by hand. The global
        // compliance_rules seed is left intact (it is shared reference data).
        inCommittedTx(() -> {
            entityManager.createNativeQuery(
                            "DELETE FROM inspections WHERE organization_id = :org")
                    .setParameter("org", orgAId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM document_sequence WHERE organization_id = :org")
                    .setParameter("org", orgAId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM project WHERE organization_id = :org")
                    .setParameter("org", orgAId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM organization WHERE id = :org")
                    .setParameter("org", orgAId).executeUpdate();
        });
    }

    @Test
    void generatesSuggestedComplianceInspections_andIsIdempotent() {
        List<InspectionDto> created = service.generateForProject(projectId, orgAId);

        // Exactly the two applicable rules, ordered by phase: pre-construction then post.
        assertThat(created).hasSize(2);
        assertThat(created).allSatisfy(dto -> {
            assertThat(dto.type()).isEqualTo(InspectionType.COMPLIANCE);
            assertThat(dto.status()).isEqualTo(InspectionStatus.SUGGESTED);
            assertThat(dto.origin()).isEqualTo(InspectionOrigin.AI_GENERATED);
            assertThat(dto.projectId()).isEqualTo(projectId);
            assertThat(dto.inspectionNumber()).startsWith("INSP-");
            assertThat(dto.riskLevel()).isEqualTo(ComplianceRiskLevel.CRITICAL);
        });
        assertThat(created).extracting(InspectionDto::complianceRuleRef)
                .containsExactly(RULE_PRE, RULE_POST);
        assertThat(created).extracting(dto -> dto.compliancePhase().getValue())
                .containsExactly("pre-construction", "post-construction");
        assertThat(created.get(0).aiRationale()).isEqualTo("Required before work starts");

        entityManager.flush();

        // Re-run: everything already exists, so nothing new is created.
        List<InspectionDto> rerun = service.generateForProject(projectId, orgAId);
        assertThat(rerun).isEmpty();

        Long total = ((Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM inspections WHERE organization_id = :org AND type = 'COMPLIANCE'")
                .setParameter("org", orgAId)
                .getSingleResult()).longValue();
        assertThat(total).isEqualTo(2L);
    }

    private void inCommittedTx(Runnable work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> work.run());
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
