package org.tornotron.echno_backend.inventoryTransaction;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the stock valuation math in {@link InventoryService#updateCurrentStock}
 * against a real CockroachDB: an inbound receipt adds cost at its own unit price and
 * an outbound issue is valued at the moving weighted-average cost. The concurrency
 * ITs cover the locking; this covers the numbers the locking protects.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(InventoryService.class)
class InventoryValuationIT extends AbstractIntegrationTest {

    @Autowired
    private InventoryService inventoryService;

    @PersistenceContext
    private EntityManager entityManager;

    private Organization org;
    private Material material;
    private Project project;

    @BeforeEach
    void seed() {
        org = new Organization();
        org.setOrganizationName("Valuation Org");
        org.setOrganizationAddress("addr");
        org.setOrganizationEmail("valuation@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);

        material = new Material();
        material.setMaterialName("Cement");
        material.setUnit("bag");
        material.setOrganization(org);
        entityManager.persist(material);

        project = new Project();
        project.setProjectName("Project 1");
        project.setOrganization(org);
        entityManager.persist(project);

        entityManager.flush();
    }

    @Test
    void inboundReceipt_addsQuantityAndValueAtItsUnitCost() {
        inventoryService.updateCurrentStock(material, project, null, org, 100.0, bd("10"));

        assertThat(inventoryService.getCurrentStock(material.getId(), project.getId())).isEqualTo(100.0);
        assertThat(inventoryService.getStockValue(material.getId(), project.getId()))
                .isEqualByComparingTo("1000.00");
        assertThat(inventoryService.getAverageCost(material.getId(), project.getId(), null))
                .isEqualByComparingTo("10.00");
    }

    @Test
    void twoReceiptsAtDifferentCosts_giveWeightedAverageCost() {
        inventoryService.updateCurrentStock(material, project, null, org, 100.0, bd("10")); // value 1000
        inventoryService.updateCurrentStock(material, project, null, org, 100.0, bd("20")); // value +2000 = 3000

        // (1000 + 2000) / 200 = 15.00
        assertThat(inventoryService.getCurrentStock(material.getId(), project.getId())).isEqualTo(200.0);
        assertThat(inventoryService.getStockValue(material.getId(), project.getId()))
                .isEqualByComparingTo("3000.00");
        assertThat(inventoryService.getAverageCost(material.getId(), project.getId(), null))
                .isEqualByComparingTo("15.00");
    }

    @Test
    void outboundIssue_reducesValueAtWeightedAverageCost() {
        inventoryService.updateCurrentStock(material, project, null, org, 100.0, bd("10"));
        inventoryService.updateCurrentStock(material, project, null, org, 100.0, bd("20")); // avg cost 15
        inventoryService.updateCurrentStock(material, project, null, org, -50.0, null);      // issue 50 @ 15

        // qty 150; value 3000 - (15 * 50) = 2250; avg cost unchanged at 15
        assertThat(inventoryService.getCurrentStock(material.getId(), project.getId())).isEqualTo(150.0);
        assertThat(inventoryService.getStockValue(material.getId(), project.getId()))
                .isEqualByComparingTo("2250.00");
        assertThat(inventoryService.getAverageCost(material.getId(), project.getId(), null))
                .isEqualByComparingTo("15.00");
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
