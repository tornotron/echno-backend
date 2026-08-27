package org.tornotron.echno_backend.inventoryTransaction;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.inventoryTransaction.dto.MaterialMovementHistoryDto;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType.StockEffect;
import org.tornotron.echno_backend.inventoryTransaction.mapper.InventoryTransactionMapper;
import org.tornotron.echno_backend.inventoryTransaction.mapper.InventoryTransactionMapperImpl;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.enums.StorageLocationType;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the material movement-history finder that backs the Location module timeline
 * (issue #256): it returns only the requested material's ledger rows, oldest movement first,
 * each row resolving its storage location, project, running balance, creator and movement
 * direction, and it does so without a per-row query. Runs against a real CockroachDB through
 * the shared Testcontainer.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MaterialMovementHistoryIT extends AbstractIntegrationTest {

    @Autowired
    private InventoryTransactionRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * The mapper is built by hand rather than injected: this is a repository slice, so the
     * MapStruct bean is not in the context. {@code toMovementHistoryDto} maps every field
     * from a source path of its own and never delegates to the employee mapper, so the
     * uninjected collaborator on the generated implementation is not reached.
     */
    private final InventoryTransactionMapper mapper = new InventoryTransactionMapperImpl();

    @Test
    void movementHistory_returnsMaterialRowsOldestFirstWithLocationAndDirection() {
        Fixture fixture = persistFixture();

        Page<InventoryTransaction> page =
                repository.findMovementHistoryByMaterial(fixture.cementId, PageRequest.of(0, 10));

        List<InventoryTransaction> rows = page.getContent();

        // Only the cement rows, and all of them.
        assertThat(rows).hasSize(4);
        assertThat(rows).allSatisfy(row -> assertThat(row.getMaterial().getId()).isEqualTo(fixture.cementId));
        assertThat(page.getTotalElements()).isEqualTo(4);

        // Oldest movement first.
        assertThat(rows).extracting(InventoryTransaction::getReferenceNumber)
                .containsExactly("GRN-1", "TRF-2", "USE-3", "ADJ-4");
        assertThat(rows).extracting(InventoryTransaction::getTransactionDate).isSorted();

        // Storage location resolves on each row.
        assertThat(rows).extracting(row -> row.getStorageLocation().getLocationName())
                .containsExactly("Site A main store", "Site A yard", "Site A main store", "Site A yard");

        // Direction comes from the transaction type's stock effect.
        assertThat(rows).extracting(row -> row.getTransactionType().getStockEffect())
                .containsExactly(StockEffect.INCREASE, StockEffect.DECREASE, StockEffect.DECREASE, StockEffect.EITHER);
    }

    @Test
    void movementHistory_dtoCarriesBalanceProjectAndCreator() {
        Fixture fixture = persistFixture();

        List<MaterialMovementHistoryDto> history =
                repository.findMovementHistoryByMaterial(fixture.cementId, PageRequest.of(0, 10))
                        .map(mapper::toMovementHistoryDto)
                        .getContent();

        assertThat(history).hasSize(4);

        MaterialMovementHistoryDto received = history.get(0);
        assertThat(received.getReferenceNumber()).isEqualTo("GRN-1");
        assertThat(received.getOpeningStock()).isEqualTo(0.0);
        assertThat(received.getQuantityChanged()).isEqualTo(100.0);
        assertThat(received.getClosingStock()).isEqualTo(100.0);
        assertThat(received.getCreatedByName()).isEqualTo("Asha Menon");
        assertThat(received.getStorageLocationName()).isEqualTo("Site A main store");
        assertThat(received.getProjectName()).isEqualTo("Timeline Project");
        assertThat(received.getDirection()).isEqualTo(StockEffect.INCREASE);

        // The balance chains across the timeline, and the creator alternates.
        assertThat(history).extracting(MaterialMovementHistoryDto::getOpeningStock)
                .containsExactly(0.0, 100.0, 80.0, 65.0);
        assertThat(history).extracting(MaterialMovementHistoryDto::getClosingStock)
                .containsExactly(100.0, 80.0, 65.0, 70.0);
        assertThat(history).extracting(MaterialMovementHistoryDto::getCreatedByName)
                .containsExactly("Asha Menon", "Ravi Kumar", "Asha Menon", "Ravi Kumar");
    }

    @Test
    void movementHistory_leavesTheCreatorNameNullWhenTheRowHasNoCreator() {
        Fixture fixture = persistFixture();

        InventoryTransaction anonymous = new InventoryTransaction();
        anonymous.setOrganization(entityManager.getReference(Organization.class, fixture.orgId));
        anonymous.setProject(entityManager.getReference(Project.class, fixture.projectId));
        anonymous.setMaterial(entityManager.getReference(Material.class, fixture.cementId));
        anonymous.setStorageLocation(entityManager.getReference(StorageLocation.class, fixture.mainStoreId));
        anonymous.setTransactionType(InventoryTransactionType.OPENING_BALANCE);
        anonymous.setQuantityChanged(0.0);
        anonymous.setOpeningStock(0.0);
        anonymous.setClosingStock(0.0);
        anonymous.setTransactionDate(LocalDateTime.of(2026, 7, 1, 9, 0));
        anonymous.setReferenceNumber("OB-0");
        entityManager.persist(anonymous);
        entityManager.flush();
        entityManager.clear();

        List<MaterialMovementHistoryDto> history =
                repository.findMovementHistoryByMaterial(fixture.cementId, PageRequest.of(0, 10))
                        .map(mapper::toMovementHistoryDto)
                        .getContent();

        // The uncredited opening balance predates the rest, so it leads the timeline.
        assertThat(history.get(0).getReferenceNumber()).isEqualTo("OB-0");
        assertThat(history.get(0).getCreatedByName()).isNull();
    }

    /**
     * Pins the fetch join. Every association the timeline DTO reads is initialised by the page
     * query itself, and the statement count does not grow with the number of rows on the page:
     * a page of two rows and a page of four, both covering the same locations and creators,
     * issue exactly the same number of statements. Without the fetch join the four-row page
     * would issue six more than the two-row one.
     */
    @Test
    void movementHistory_doesNotQueryPerRow() {
        Fixture fixture = persistFixture();

        SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();
        boolean statisticsWereEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            long twoRowPage = statementsToReadHistory(statistics, fixture.cementId, 2);
            long fourRowPage = statementsToReadHistory(statistics, fixture.cementId, 4);

            assertThat(fourRowPage)
                    .as("statements for a four-row page (%d) must match a two-row page (%d): "
                            + "a per-row query would make the larger page cost more",
                            fourRowPage, twoRowPage)
                    .isEqualTo(twoRowPage);
        } finally {
            statistics.setStatisticsEnabled(statisticsWereEnabled);
        }
    }

    /**
     * Reads one page of the history and reports how many statements it took, touching every
     * association the DTO mapping touches. Asserts on the way through that none of them needed
     * a lazy load, which is what the fetch join is there to guarantee.
     */
    private long statementsToReadHistory(Statistics statistics, Long materialId, int pageSize) {
        entityManager.clear();
        statistics.clear();

        List<InventoryTransaction> rows =
                repository.findMovementHistoryByMaterial(materialId, PageRequest.of(0, pageSize)).getContent();

        assertThat(rows).hasSize(pageSize);
        assertThat(rows).allSatisfy(row -> {
            assertThat(Hibernate.isInitialized(row.getStorageLocation())).isTrue();
            assertThat(Hibernate.isInitialized(row.getProject())).isTrue();
            assertThat(Hibernate.isInitialized(row.getCreatedBy())).isTrue();
        });

        // Read the fields the mapper reads, so any deferred load would show up in the count.
        rows.forEach(row -> {
            row.getStorageLocation().getLocationName();
            row.getProject().getProjectName();
            row.getCreatedBy().getEmployeeName();
        });

        return statistics.getPrepareStatementCount();
    }

    /**
     * Four cement movements with a chained balance, alternating between two storage locations
     * and two creators, plus one steel movement that must not leak into the history. The two
     * locations and two creators alternate so that a two-row page already covers the same
     * distinct set of related rows a four-row page does.
     */
    private Fixture persistFixture() {
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

        Employee asha = employee(org, "Asha Menon", "asha@example.test");
        Employee ravi = employee(org, "Ravi Kumar", "ravi@example.test");
        entityManager.persist(asha);
        entityManager.persist(ravi);

        // Insert out of date order to prove the query, not the insert order, decides the timeline.
        LocalDateTime base = LocalDateTime.of(2026, 8, 1, 9, 0);
        persistTransaction(org, project, cement, mainStore, asha, InventoryTransactionType.USE,
                80.0, -15.0, 65.0, base.plusDays(2), "USE-3");
        persistTransaction(org, project, cement, mainStore, asha, InventoryTransactionType.GRN,
                0.0, 100.0, 100.0, base, "GRN-1");
        persistTransaction(org, project, cement, yard, ravi, InventoryTransactionType.TRANSFER_OUT,
                100.0, -20.0, 80.0, base.plusDays(1), "TRF-2");
        persistTransaction(org, project, cement, yard, ravi, InventoryTransactionType.ADJUST,
                65.0, 5.0, 70.0, base.plusDays(3), "ADJ-4");
        // A different material must not leak into the history.
        persistTransaction(org, project, steel, yard, ravi, InventoryTransactionType.GRN,
                0.0, 5.0, 5.0, base.plusDays(1), "GRN-STEEL");
        entityManager.flush();
        entityManager.clear();

        return new Fixture(org.getId(), project.getId(), cement.getId(), mainStore.getId());
    }

    private StorageLocation location(Organization org, Project project, String name) {
        StorageLocation location = new StorageLocation();
        location.setLocationName(name);
        location.setLocationType(StorageLocationType.WAREHOUSE);
        location.setOrganization(org);
        location.setProject(project);
        return location;
    }

    private Employee employee(Organization org, String name, String email) {
        User user = new User();
        user.setKeycloakId("kc-" + email);
        user.setName(name);
        entityManager.persist(user);

        Employee employee = new Employee();
        employee.setUser(user);
        employee.setEmployeeName(name);
        employee.setGender("unspecified");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress(email);
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        employee.setOrganization(org);
        return employee;
    }

    private void persistTransaction(Organization org, Project project, Material material,
                                    StorageLocation location, Employee createdBy,
                                    InventoryTransactionType type, double openingStock,
                                    double quantityChanged, double closingStock,
                                    LocalDateTime when, String reference) {
        InventoryTransaction txn = new InventoryTransaction();
        txn.setOrganization(org);
        txn.setProject(project);
        txn.setMaterial(material);
        txn.setStorageLocation(location);
        txn.setCreatedBy(createdBy);
        txn.setTransactionType(type);
        txn.setOpeningStock(openingStock);
        txn.setQuantityChanged(quantityChanged);
        txn.setClosingStock(closingStock);
        txn.setTransactionDate(when);
        txn.setReferenceNumber(reference);
        entityManager.persist(txn);
    }

    /** Ids of the fixture rows, read back after the persistence context is cleared. */
    private record Fixture(Long orgId, Long projectId, Long cementId, Long mainStoreId) {
    }
}
