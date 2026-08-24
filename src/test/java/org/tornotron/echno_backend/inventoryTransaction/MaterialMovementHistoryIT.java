package org.tornotron.echno_backend.inventoryTransaction;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType.StockEffect;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.enums.StorageLocationType;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the material movement-history finder that backs the Location module timeline
 * (issue #256): it returns only the requested material's ledger rows, oldest movement first,
 * and each row resolves its storage location and carries a movement direction. Runs against a
 * real CockroachDB through the shared Testcontainer.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MaterialMovementHistoryIT extends AbstractIntegrationTest {

    @Autowired
    private InventoryTransactionRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void movementHistory_returnsMaterialRowsOldestFirstWithLocationAndDirection() {
        Organization org = new Organization();
        org.setOrganizationName("History Org");
        org.setOrganizationAddress("addr");
        org.setOrganizationEmail("history@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);

        Project project = new Project();
        project.setProjectName("Timeline Project");
        project.setOrganization(org);
        entityManager.persist(project);

        Material cement = new Material();
        cement.setMaterialName("Cement");
        cement.setUnit("bag");
        cement.setOrganization(org);
        entityManager.persist(cement);

        Material steel = new Material();
        steel.setMaterialName("Steel");
        steel.setUnit("ton");
        steel.setOrganization(org);
        entityManager.persist(steel);

        StorageLocation mainStore = location(org, project, "Site A main store");
        StorageLocation yard = location(org, project, "Site A yard");
        entityManager.persist(mainStore);
        entityManager.persist(yard);

        // Insert out of date order to prove the query, not the insert order, decides the timeline.
        LocalDateTime base = LocalDateTime.of(2026, 8, 1, 9, 0);
        persistTransaction(org, project, cement, yard, InventoryTransactionType.USE, -15.0, base.plusDays(2), "USE-3");
        persistTransaction(org, project, cement, mainStore, InventoryTransactionType.GRN, 100.0, base, "GRN-1");
        persistTransaction(org, project, cement, mainStore, InventoryTransactionType.TRANSFER_OUT, -20.0, base.plusDays(1), "TRF-2");
        // A different material must not leak into the history.
        persistTransaction(org, project, steel, yard, InventoryTransactionType.GRN, 5.0, base.plusDays(1), "GRN-STEEL");
        entityManager.flush();
        entityManager.clear();

        Page<InventoryTransaction> page =
                repository.findMovementHistoryByMaterial(cement.getId(), PageRequest.of(0, 10));

        List<InventoryTransaction> rows = page.getContent();

        // Only the cement rows, and all of them.
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> assertThat(row.getMaterial().getId()).isEqualTo(cement.getId()));

        // Oldest movement first.
        assertThat(rows).extracting(InventoryTransaction::getReferenceNumber)
                .containsExactly("GRN-1", "TRF-2", "USE-3");
        assertThat(rows).extracting(InventoryTransaction::getTransactionDate).isSorted();

        // Storage location resolves on each row.
        assertThat(rows).extracting(row -> row.getStorageLocation().getLocationName())
                .containsExactly("Site A main store", "Site A main store", "Site A yard");

        // Direction comes from the transaction type's stock effect.
        assertThat(rows).extracting(row -> row.getTransactionType().getStockEffect())
                .containsExactly(StockEffect.INCREASE, StockEffect.DECREASE, StockEffect.DECREASE);
    }

    private StorageLocation location(Organization org, Project project, String name) {
        StorageLocation location = new StorageLocation();
        location.setLocationName(name);
        location.setLocationType(StorageLocationType.WAREHOUSE);
        location.setOrganization(org);
        location.setProject(project);
        return location;
    }

    private void persistTransaction(Organization org, Project project, Material material,
                                    StorageLocation location, InventoryTransactionType type,
                                    double quantityChanged, LocalDateTime when, String reference) {
        InventoryTransaction txn = new InventoryTransaction();
        txn.setOrganization(org);
        txn.setProject(project);
        txn.setMaterial(material);
        txn.setStorageLocation(location);
        txn.setTransactionType(type);
        txn.setQuantityChanged(quantityChanged);
        txn.setTransactionDate(when);
        txn.setReferenceNumber(reference);
        entityManager.persist(txn);
    }
}
