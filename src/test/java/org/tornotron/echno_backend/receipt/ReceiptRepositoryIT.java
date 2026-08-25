package org.tornotron.echno_backend.receipt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ReceiptRepository} against a real CockroachDB
 * (see {@link AbstractIntegrationTest}). Asserts the tenant-scoped lookup and that the
 * paginated search runs on its null-search path, which is the one that used to break on
 * CockroachDB when the pattern was built with SQL {@code ||}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ReceiptRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private ReceiptRepository receiptRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void findByIdAndOrganization_returnsTheReceipt() {
        Organization org = persistOrganization("Org A");
        Receipt receipt = persistReceipt(org, "RCP-2027-000001", "Asset Homes Pvt Ltd", "issued");
        em.flush();
        em.clear();

        Optional<Receipt> found = receiptRepository.findByIdAndOrganization_Id(receipt.getId(), org.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getReceivedFrom()).isEqualTo("Asset Homes Pvt Ltd");
        assertThat(found.get().getReceiptNumber()).isEqualTo("RCP-2027-000001");
        assertThat(found.get().getOrganization().getId()).isEqualTo(org.getId());
    }

    @Test
    void findByIdAndOrganization_doesNotReturnAcrossTenants() {
        Organization orgA = persistOrganization("Org A2");
        Organization orgB = persistOrganization("Org B2");
        Receipt receipt = persistReceipt(orgA, "RCP-2027-000002", "CREDAI Kerala", "draft");
        em.flush();
        em.clear();

        Optional<Receipt> found = receiptRepository.findByIdAndOrganization_Id(receipt.getId(), orgB.getId());

        assertThat(found).isEmpty();
    }

    @Test
    void search_withNullSearchAndStatus_includesThePersistedReceipt() {
        Organization org = persistOrganization("Org C");
        persistReceipt(org, "RCP-2027-000003", "Site engineer", "cancelled");
        em.flush();
        em.clear();

        // The no-filter path: both binds are null, so the query takes its IS NULL branch.
        // This is the case that failed on CockroachDB when the LIKE pattern was assembled
        // with SQL CONCAT/||, so exercising it here keeps that regression covered.
        Page<Receipt> result = receiptRepository.search(null, null, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Receipt::getReceiptNumber)
                .contains("RCP-2027-000003");
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        em.persist(org);
        return org;
    }

    private Receipt persistReceipt(Organization org, String number, String receivedFrom, String status) {
        Receipt receipt = new Receipt();
        receipt.setOrganization(org);
        receipt.setReceiptNumber(number);
        receipt.setType("payment");
        receipt.setStatus(status);
        receipt.setReceivedFrom(receivedFrom);
        receipt.setAmount(new BigDecimal("45000.00"));
        receipt.setCurrency("INR");
        receipt.setReceiptDate(LocalDate.of(2026, 8, 20));
        em.persist(receipt);
        return receipt;
    }
}
