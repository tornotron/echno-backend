package org.tornotron.echno_backend.finance.construction;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.configuration.JpaAuditingConfig;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceDto;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceLineRequest;
import org.tornotron.echno_backend.finance.construction.dtos.CreateConstructionInvoiceRequest;
import org.tornotron.echno_backend.finance.construction.dtos.UpdateConstructionInvoiceRequest;
import org.tornotron.echno_backend.finance.construction.mapper.ConstructionInvoiceMapperImpl;
import org.tornotron.echno_backend.finance.construction.repositories.ConstructionInvoiceRepository;
import org.tornotron.echno_backend.finance.construction.service.ConstructionInvoiceService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end exercise of the construction invoice CRUD path against a real
 * CockroachDB: create computes the money totals from the line items, get and list
 * return them, update recomputes them, and the org filter keeps one tenant's
 * invoices invisible to another.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ConstructionInvoiceService.class, ConstructionInvoiceMapperImpl.class,
        TenantEntityHelper.class, EntryNumberGenerator.class, JpaAuditingConfig.class})
class ConstructionInvoiceServiceIT extends AbstractIntegrationTest {

    @Autowired
    private ConstructionInvoiceService service;

    @Autowired
    private ConstructionInvoiceRepository invoiceRepo;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long orgAId;
    private Long orgBId;
    private Long projectId;

    // The tenant seed (orgs + project) must be committed, not held in the test's
    // rolled-back transaction: EntryNumberGenerator.next() runs in REQUIRES_NEW, so
    // the document_sequence insert commits in a separate transaction that only sees
    // committed rows. The invoice itself stays in the rolled-back test transaction.
    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization orgA = persistOrganization("Org A");
            Organization orgB = persistOrganization("Org B");

            Project project = new Project();
            project.setProjectName("Tower A");
            project.setOrganization(orgA);
            entityManager.persist(project);

            entityManager.flush();
            orgAId = orgA.getId();
            orgBId = orgB.getId();
            projectId = project.getId();
        });

        TenantContext.setCurrentOrgId(orgAId);
    }

    @AfterEach
    void cleanup() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
        TenantContext.clear();
        if (orgAId == null && orgBId == null) {
            return;
        }
        // Committed seed rows survive the test rollback, so remove them by hand in
        // FK-safe order (also clears the committed document_sequence rows).
        inCommittedTx(() -> {
            deleteForOrgs("DELETE FROM construction_invoice_lines WHERE invoice_id IN "
                    + "(SELECT id FROM construction_invoices WHERE organization_id IN (:a,:b))");
            deleteForOrgs("DELETE FROM construction_invoices WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM document_sequence WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM project WHERE organization_id IN (:a,:b)");
            deleteForOrgs("DELETE FROM organization WHERE id IN (:a,:b)");
        });
    }

    private void deleteForOrgs(String sql) {
        entityManager.createNativeQuery(sql)
                .setParameter("a", orgAId)
                .setParameter("b", orgBId)
                .executeUpdate();
    }

    private void inCommittedTx(Runnable work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> work.run());
    }

    @Test
    void create_get_list_update_computesTotalsAndScopesByTenant() {
        CreateConstructionInvoiceRequest createReq = new CreateConstructionInvoiceRequest(
                ConstructionInvoiceType.SALES,
                projectId,
                null, null, null,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                "Net 30", "Bank Transfer", "29ABCDE1234F1Z5", "GST",
                "First running bill", "Standard terms apply",
                List.of(
                        new ConstructionInvoiceLineRequest("Cement supply", bd("2"), "nos",
                                bd("100"), bd("18"), null, null, null, null),
                        new ConstructionInvoiceLineRequest("Steel supply", bd("1"), "lot",
                                bd("50"), bd("18"), bd("10"), null, null, null)
                )
        );

        // create - totals computed from the lines, status forced to DRAFT/UNPAID
        ConstructionInvoiceDto created = service.create(createReq);
        assertThat(created.status()).isEqualTo(ConstructionInvoiceStatus.DRAFT);
        assertThat(created.paymentStatus()).isEqualTo(ConstructionPaymentStatus.UNPAID);
        assertThat(created.invoiceNumber()).startsWith("CINV-");
        assertThat(created.lines()).hasSize(2);
        assertThat(created.subtotal()).isEqualByComparingTo(bd("250"));
        assertThat(created.discountAmount()).isEqualByComparingTo(bd("5"));
        assertThat(created.taxAmount()).isEqualByComparingTo(bd("44.1"));
        assertThat(created.totalAmount()).isEqualByComparingTo(bd("289.1"));
        assertThat(created.paidAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(created.balanceAmount()).isEqualByComparingTo(bd("289.1"));

        UUID id = created.id();

        // get
        ConstructionInvoiceDto fetched = service.findById(id);
        assertThat(fetched.id()).isEqualTo(id);
        assertThat(fetched.totalAmount()).isEqualByComparingTo(bd("289.1"));
        assertThat(fetched.lines()).hasSize(2);

        entityManager.flush();
        entityManager.clear();

        Pageable pageable = PageRequest.of(0, 10);

        // tenant scoping - invisible to another organization
        enableOrgFilter(orgBId);
        assertThat(service.findAll(null, null, null, null, pageable).getTotalElements()).isZero();
        assertThat(invoiceRepo.findByIdWithLines(id)).isEmpty();

        // visible and listable to the owning organization
        disableOrgFilter();
        enableOrgFilter(orgAId);
        assertThat(service.findAll(null, null, null, null, pageable).getTotalElements()).isEqualTo(1);
        assertThat(service.findAll(projectId, null, ConstructionInvoiceStatus.DRAFT,
                ConstructionInvoiceType.SALES, pageable).getTotalElements()).isEqualTo(1);
        assertThat(invoiceRepo.findByIdWithLines(id)).isPresent();
        disableOrgFilter();

        // update - status set directly, totals recomputed from the new single line
        UpdateConstructionInvoiceRequest updateReq = new UpdateConstructionInvoiceRequest(
                ConstructionInvoiceType.SALES,
                ConstructionInvoiceStatus.SENT,
                ConstructionPaymentStatus.UNPAID,
                projectId,
                null, null, null,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                null,
                "Net 30", "Bank Transfer", "29ABCDE1234F1Z5", "GST",
                "Revised running bill", "Standard terms apply",
                List.of(
                        new ConstructionInvoiceLineRequest("Cement supply", bd("3"), "nos",
                                bd("100"), bd("18"), null, null, null, null)
                )
        );

        ConstructionInvoiceDto updated = service.update(id, updateReq);
        assertThat(updated.status()).isEqualTo(ConstructionInvoiceStatus.SENT);
        assertThat(updated.lines()).hasSize(1);
        // The replaced line must be flushed so it carries a generated id; otherwise
        // the client-side parser rejects the null id and the update looks failed.
        assertThat(updated.lines().getFirst().id()).isNotNull();
        assertThat(updated.subtotal()).isEqualByComparingTo(bd("300"));
        assertThat(updated.taxAmount()).isEqualByComparingTo(bd("54"));
        assertThat(updated.discountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(updated.totalAmount()).isEqualByComparingTo(bd("354"));
        assertThat(updated.balanceAmount()).isEqualByComparingTo(bd("354"));
    }

    private void enableOrgFilter(Long orgId) {
        entityManager.unwrap(Session.class)
                .enableFilter("orgFilter")
                .setParameter("organizationId", orgId);
    }

    private void disableOrgFilter() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
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

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
