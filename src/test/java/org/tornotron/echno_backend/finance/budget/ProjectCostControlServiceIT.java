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
import org.tornotron.echno_backend.finance.budget.domain.BudgetAllocation;
import org.tornotron.echno_backend.finance.budget.domain.CostCategory;
import org.tornotron.echno_backend.finance.budget.dtos.ProjectCostControlDto;
import org.tornotron.echno_backend.finance.budget.dtos.ProjectCostControlLineDto;
import org.tornotron.echno_backend.finance.budget.service.ProjectCostControlService;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceStatus;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentStatus;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionInvoice;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionInvoiceLine;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the project cost-control roll-up against a real CockroachDB: allocated, committed and
 * spent are summed per budget head from the tagged invoice lines, remaining is derived, and the
 * over-budget flag is raised where committed plus spent passes the allocation. Committed counts an
 * approved-but-unpaid invoice; spent counts a fully paid one; a draft invoice is excluded; and a head
 * with tagged spend but no allocation still surfaces through the union.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProjectCostControlService.class, JpaAuditingConfig.class})
class ProjectCostControlServiceIT extends AbstractIntegrationTest {

    @Autowired
    private ProjectCostControlService service;

    @PersistenceContext
    private EntityManager entityManager;

    private Organization org;
    private Long projectId;
    private CostCategory materials;
    private CostCategory labour;
    private CostCategory plant;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        org = persistOrganization("Cost Control Org");
        entityManager.flush();
        TenantContext.setCurrentOrgId(org.getId());

        Project project = new Project();
        project.setProjectName("Tower A");
        project.setOrganization(org);
        entityManager.persist(project);
        projectId = project.getId();

        materials = persistCategory("Materials");
        labour = persistCategory("Labour");
        plant = persistCategory("Plant");

        // Budget: Materials and Labour are allocated; Plant deliberately has none, to test the union.
        persistAllocation(project, materials, "400000");
        persistAllocation(project, labour, "200000");

        // Materials: an approved-unpaid invoice (committed) and a fully-paid one (spent).
        persistInvoice("CINV-A", ConstructionInvoiceStatus.APPROVED, ConstructionPaymentStatus.UNPAID,
                materials, "120000");
        persistInvoice("CINV-B", ConstructionInvoiceStatus.APPROVED, ConstructionPaymentStatus.PAID,
                materials, "300000");
        // Labour: only a draft invoice, which must not count as either committed or spent.
        persistInvoice("CINV-C", ConstructionInvoiceStatus.DRAFT, ConstructionPaymentStatus.UNPAID,
                labour, "50000");
        // Plant: a paid invoice with no allocation, so it surfaces only through spend.
        persistInvoice("CINV-D", ConstructionInvoiceStatus.APPROVED, ConstructionPaymentStatus.PAID,
                plant, "50000");

        entityManager.flush();
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void costControl_rollsUpAllocatedCommittedSpentAndRemaining() {
        ProjectCostControlDto view = service.getForProject(projectId);

        assertThat(view.projectId()).isEqualTo(projectId);
        // Materials + Labour + Plant: Labour has no spend but has an allocation; Plant no allocation.
        assertThat(view.categories()).hasSize(3);

        ProjectCostControlLineDto mat = lineFor(view, "Materials");
        assertThat(mat.allocated()).isEqualByComparingTo("400000");
        assertThat(mat.committed()).isEqualByComparingTo("120000");
        assertThat(mat.spent()).isEqualByComparingTo("300000");
        assertThat(mat.remaining()).isEqualByComparingTo("-20000");   // 400000 - 120000 - 300000
        assertThat(mat.overBudget()).isTrue();                        // 420000 > 400000

        ProjectCostControlLineDto lab = lineFor(view, "Labour");
        assertThat(lab.allocated()).isEqualByComparingTo("200000");
        assertThat(lab.committed()).isEqualByComparingTo("0");        // draft invoice excluded
        assertThat(lab.spent()).isEqualByComparingTo("0");
        assertThat(lab.remaining()).isEqualByComparingTo("200000");
        assertThat(lab.overBudget()).isFalse();

        ProjectCostControlLineDto plt = lineFor(view, "Plant");
        assertThat(plt.allocated()).isEqualByComparingTo("0");        // no allocation, union via spend
        assertThat(plt.committed()).isEqualByComparingTo("0");
        assertThat(plt.spent()).isEqualByComparingTo("50000");
        assertThat(plt.remaining()).isEqualByComparingTo("-50000");
        assertThat(plt.overBudget()).isTrue();

        ProjectCostControlLineDto totals = view.totals();
        assertThat(totals.costCategoryId()).isNull();
        assertThat(totals.allocated()).isEqualByComparingTo("600000");
        assertThat(totals.committed()).isEqualByComparingTo("120000");
        assertThat(totals.spent()).isEqualByComparingTo("350000");
        assertThat(totals.remaining()).isEqualByComparingTo("130000");  // 600000 - 120000 - 350000
        assertThat(totals.overBudget()).isFalse();                      // 470000 < 600000
    }

    // --- Helpers ----------------------------------------------------------

    private ProjectCostControlLineDto lineFor(ProjectCostControlDto view, String name) {
        return view.categories().stream()
                .filter(l -> l.costCategoryName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No cost-control line for " + name));
    }

    private CostCategory persistCategory(String name) {
        CostCategory category = new CostCategory();
        category.setName(name);
        category.setActive(true);
        category.setOrganization(org);
        entityManager.persist(category);
        return category;
    }

    private void persistAllocation(Project project, CostCategory category, String amount) {
        BudgetAllocation allocation = new BudgetAllocation();
        allocation.setProject(project);
        allocation.setCostCategory(category);
        allocation.setAllocatedAmount(new BigDecimal(amount));
        allocation.setOrganization(org);
        entityManager.persist(allocation);
    }

    private void persistInvoice(String number, ConstructionInvoiceStatus status,
                                ConstructionPaymentStatus paymentStatus, CostCategory category, String lineTotal) {
        BigDecimal total = new BigDecimal(lineTotal);

        ConstructionInvoice invoice = new ConstructionInvoice();
        invoice.setInvoiceNumber(number);
        invoice.setType(ConstructionInvoiceType.PURCHASE);
        invoice.setStatus(status);
        invoice.setPaymentStatus(paymentStatus);
        invoice.setProjectId(projectId);
        invoice.setIssueDate(LocalDate.of(2026, 8, 1));
        invoice.setDueDate(LocalDate.of(2026, 8, 31));
        invoice.setSubtotal(total);
        invoice.setTotalAmount(total);
        invoice.setOrganization(org);

        ConstructionInvoiceLine line = new ConstructionInvoiceLine();
        line.setDescription("Work for " + category.getName());
        line.setQuantity(BigDecimal.ONE);
        line.setUnit("lot");
        line.setUnitPrice(total);
        line.setSubtotal(total);
        line.setTotal(total);
        line.setCostCategory(category);
        invoice.addLine(line);

        entityManager.persist(invoice);
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
