package org.tornotron.echno_backend.inventoryTransaction;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.enums.StorageLocationType;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the two grouped reads that replaced the per-row aggregates against a real CockroachDB.
 *
 * <p>The mappers used to ask for one material's stock, or one location's item count, once per row
 * they converted. Those reads are now issued once for the whole page, which is only safe if the
 * grouped form returns exactly what the per-row form returned, including for the row that has no
 * stock at all and so produces no group. That equivalence is what this pins, and it needs the real
 * database because the constructor expression and the {@code GROUP BY} are the parts a mock cannot
 * check.
 *
 * <p>Runs on the same {@code @DataJpaTest} configuration as {@link InventoryValuationIT}, so it
 * shares that Spring context rather than adding one to a cache the suite is already tight on.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(InventoryService.class)
class CurrentStockBatchReadIT extends AbstractIntegrationTest {

    @Autowired
    private CurrentStockRepository currentStockRepository;

    @Autowired
    private InventoryService inventoryService;

    @PersistenceContext
    private EntityManager entityManager;

    private Organization org;
    private Project project;
    private Material cement;
    private Material steel;
    private Material unstocked;
    private StorageLocation siteStore;
    private StorageLocation warehouse;
    private StorageLocation emptyGodown;

    @BeforeEach
    void seed() {
        org = new Organization();
        org.setOrganizationName("Batch Read Org");
        org.setOrganizationAddress("addr");
        org.setOrganizationEmail("batchread@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);

        project = new Project();
        project.setProjectName("Batch Read Project");
        project.setOrganization(org);
        entityManager.persist(project);

        cement = material("Cement");
        steel = material("Steel");
        unstocked = material("Never stocked");

        siteStore = location("Site store");
        warehouse = location("Warehouse");
        emptyGodown = location("Empty godown");

        // Cement sits in two places, so its total only comes out right if the group sums both.
        stock(cement, siteStore, 40.0, "400.00");
        stock(cement, warehouse, 60.0, "600.00");
        stock(steel, siteStore, 15.0, "1500.00");

        entityManager.flush();
        entityManager.clear();

        TenantContext.setCurrentOrgId(org.getId());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void theGroupedStockReadMatchesTheOneItReplaced() {
        List<MaterialStockTotals> totals = currentStockRepository.sumStockByMaterialIds(
                List.of(cement.getId(), steel.getId(), unstocked.getId()));

        assertThat(totals).hasSize(2);
        for (MaterialStockTotals row : totals) {
            assertThat(row.currentStock())
                    .isEqualTo(currentStockRepository.sumCurrentQuantityByMaterial(row.materialId()));
            assertThat(row.stockValue())
                    .isEqualByComparingTo(currentStockRepository.sumStockValueByMaterial(row.materialId()));
        }
        // The material with no stock row produces no group, which is the one difference from the
        // per-material read and the reason the lookup supplies the zero.
        assertThat(totals).noneMatch(row -> row.materialId().equals(unstocked.getId()));
    }

    @Test
    void theLookupReportsZeroForAMaterialThatHoldsNoStock() {
        MaterialStockLookup lookup = inventoryService.aggregateStockFor(
                List.of(cement.getId(), steel.getId(), unstocked.getId()));

        assertThat(lookup.currentStockOf(cement.getId())).isEqualTo(100.0);
        assertThat(lookup.stockValueOf(cement.getId())).isEqualByComparingTo("1000.00");
        assertThat(lookup.currentStockOf(steel.getId())).isEqualTo(15.0);

        assertThat(lookup.currentStockOf(unstocked.getId()))
                .isEqualTo(currentStockRepository.sumCurrentQuantityByMaterial(unstocked.getId()));
        assertThat(lookup.stockValueOf(unstocked.getId()))
                .isEqualByComparingTo(currentStockRepository.sumStockValueByMaterial(unstocked.getId()));
    }

    @Test
    void theGroupedItemCountMatchesTheOneItReplaced() {
        StorageLocationItemCounts counts = inventoryService.itemCountsAt(
                List.of(siteStore.getId(), warehouse.getId(), emptyGodown.getId()));

        assertThat(counts.itemCountOf(siteStore.getId())).isEqualTo(2L);
        assertThat(counts.itemCountOf(warehouse.getId())).isEqualTo(1L);
        assertThat(counts.itemCountOf(emptyGodown.getId())).isZero();
    }

    @Test
    void anEmptySetOfIdsAsksTheDatabaseNothing() {
        // IN () is not valid SQL, so the guard matters; it also keeps a page of nothing free.
        assertThat(inventoryService.aggregateStockFor(List.of()).currentStockOf(1L)).isEqualTo(0.0);
        assertThat(inventoryService.itemCountsAt(List.of()).itemCountOf(1L)).isZero();
    }

    private Material material(String name) {
        Material material = new Material();
        material.setMaterialName(name);
        material.setUnit("bag");
        material.setOrganization(org);
        entityManager.persist(material);
        return material;
    }

    private StorageLocation location(String name) {
        StorageLocation location = new StorageLocation();
        location.setLocationName(name);
        location.setLocationType(StorageLocationType.WAREHOUSE);
        location.setOrganization(org);
        location.setProject(project);
        entityManager.persist(location);
        return location;
    }

    private void stock(Material material, StorageLocation location, double quantity, String value) {
        CurrentStock row = new CurrentStock();
        row.setOrganization(org);
        row.setProject(project);
        row.setMaterial(material);
        row.setStorageLocation(location);
        row.setCurrentQuantity(quantity);
        row.setStockValue(new BigDecimal(value));
        entityManager.persist(row);
    }
}
