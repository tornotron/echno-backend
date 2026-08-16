package org.tornotron.echno_backend.stockAdjustment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link StockAdjustmentRepository} against a real CockroachDB
 * (see {@link AbstractIntegrationTest}). Asserts the tenant-scoped lookup returns the
 * header together with its line item, and that a persisted document shows up in a
 * paginated {@code findAll}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class StockAdjustmentRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private StockAdjustmentRepository stockAdjustmentRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void findByIdAndOrganization_returnsTheDocumentWithItsLineItem() {
        Organization org = persistOrganization("Org A");
        StockAdjustment adjustment = persistStockAdjustment(org, "SA-0001");
        em.flush();
        em.clear();

        Optional<StockAdjustment> found =
                stockAdjustmentRepository.findByIdAndOrganization_Id(adjustment.getId(), org.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getAdjustmentNumber()).isEqualTo("SA-0001");
        assertThat(found.get().getOrganization().getId()).isEqualTo(org.getId());
        assertThat(found.get().getLineItems()).hasSize(1);
        assertThat(found.get().getLineItems().get(0).getDescription()).isEqualTo("Cement bags");
    }

    @Test
    void findAll_paginated_includesThePersistedDocument() {
        Organization org = persistOrganization("Org B");
        persistStockAdjustment(org, "SA-0002");
        em.flush();
        em.clear();

        Page<StockAdjustment> result = stockAdjustmentRepository.findAll(PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(StockAdjustment::getAdjustmentNumber)
                .contains("SA-0002");
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

    private StockAdjustment persistStockAdjustment(Organization org, String number) {
        StockAdjustment adjustment = new StockAdjustment();
        adjustment.setOrganization(org);
        adjustment.setAdjustmentNumber(number);
        adjustment.setType("physical_count");
        adjustment.setStatus("draft");
        adjustment.setJustification("Year-end stock count");

        StockAdjustmentLineItem item = new StockAdjustmentLineItem();
        item.setDescription("Cement bags");
        item.setSystemQuantity(100.0);
        item.setPhysicalQuantity(95.0);
        item.setAdjustmentQuantity(-5.0);
        item.setUnit("bags");
        item.setReason("write_off");
        item.setOrganization(org);
        adjustment.addLineItem(item);

        em.persist(adjustment);
        return adjustment;
    }
}
