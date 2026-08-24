package org.tornotron.echno_backend.finance.budget;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.common.configuration.JpaAuditingConfig;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.finance.budget.domain.CostCategory;
import org.tornotron.echno_backend.finance.budget.dtos.BudgetAllocationDto;
import org.tornotron.echno_backend.finance.budget.dtos.UpsertBudgetAllocationRequest;
import org.tornotron.echno_backend.finance.budget.mapper.BudgetAllocationMapperImpl;
import org.tornotron.echno_backend.finance.budget.repositories.BudgetAllocationRepository;
import org.tornotron.echno_backend.finance.budget.service.BudgetAllocationService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the project budget upsert against a real CockroachDB: setting the amount for a budget
 * head that already has an allocation replaces it rather than adding a second row, so the (project,
 * cost category) pair stays unique, and a second head produces a second row.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BudgetAllocationService.class, BudgetAllocationMapperImpl.class, TenantEntityHelper.class,
        JpaAuditingConfig.class})
class BudgetAllocationServiceIT extends AbstractIntegrationTest {

    @Autowired
    private BudgetAllocationService service;

    @Autowired
    private BudgetAllocationRepository repo;

    @PersistenceContext
    private EntityManager entityManager;

    private Long projectId;
    private UUID materialsId;
    private UUID labourId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        Organization org = persistOrganization("Budget Org");
        entityManager.flush();
        TenantContext.setCurrentOrgId(org.getId());

        Project project = new Project();
        project.setProjectName("Tower A");
        project.setOrganization(org);
        entityManager.persist(project);
        projectId = project.getId();

        materialsId = persistCategory(org, "Materials");
        labourId = persistCategory(org, "Labour");
        entityManager.flush();
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void upsert_isIdempotentPerProjectAndCategory() {
        // First allocation for Materials.
        service.upsert(projectId, materialsId, new UpsertBudgetAllocationRequest(new BigDecimal("100000")));
        // Second upsert for the same head replaces the amount, does not add a row.
        BudgetAllocationDto updated = service.upsert(projectId, materialsId,
                new UpsertBudgetAllocationRequest(new BigDecimal("250000")));
        assertThat(updated.allocatedAmount()).isEqualByComparingTo("250000");

        entityManager.flush();
        entityManager.clear();

        // Exactly one Materials allocation survives; the amount is the latest.
        assertThat(repo.findByProjectAndCategory(projectId, materialsId)).isPresent();
        List<BudgetAllocationDto> afterMaterials = service.findByProject(projectId);
        assertThat(afterMaterials).hasSize(1);
        assertThat(afterMaterials.getFirst().allocatedAmount()).isEqualByComparingTo("250000");

        // A different head produces a distinct second row.
        service.upsert(projectId, labourId, new UpsertBudgetAllocationRequest(new BigDecimal("80000")));
        entityManager.flush();
        entityManager.clear();
        assertThat(service.findByProject(projectId)).hasSize(2);
    }

    // --- Helpers ----------------------------------------------------------

    private UUID persistCategory(Organization org, String name) {
        CostCategory category = new CostCategory();
        category.setName(name);
        category.setActive(true);
        category.setOrganization(org);
        entityManager.persist(category);
        return category.getId();
    }

    private Organization persistOrganization(String name) {
        Organization organization = new Organization();
        organization.setOrganizationName(name);
        organization.setOrganizationAddress(name + " address");
        organization.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        organization.setOrganizationPhone("0000000000");
        entityManager.persist(organization);
        return organization;
    }
}
