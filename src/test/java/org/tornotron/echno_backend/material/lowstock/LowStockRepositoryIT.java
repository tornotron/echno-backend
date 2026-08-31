package org.tornotron.echno_backend.material.lowstock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.inventoryTransaction.CurrentStock;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.threshold.MaterialLocationThreshold;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.enums.StorageLocationType;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reorder-level comparison against a real CockroachDB.
 *
 * <p>Every case here is one the web app's client-side comparison gets wrong or cannot see, so the
 * assertions are as much a statement of what the endpoint is for as they are a regression net. The
 * ones that would pass just as happily against a naive query are the two joins and the boundary:
 * an inner join at organization scope silently loses the material with no stock row at all, which
 * is the most depleted material there can be, and a strict {@code <} loses the material sitting
 * exactly on its level, which is the moment the level exists to name.
 *
 * <p>Runs under the same {@code @DataJpaTest} configuration as the other repository tests, so it
 * shares their Spring context rather than adding one.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LowStockRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private LowStockRepository lowStockRepository;

    @Autowired
    private TestEntityManager em;

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 50);

    @Test
    @DisplayName("organization scope reports a material that has no stock row anywhere")
    void organizationScope_reportsAMaterialWithNoStockRowAtAll() {
        Organization org = persistOrganization("no-stock-row");
        Material bricks = persistMaterial(org, "BRK-1", "Red Bricks", 25000.0);
        em.flush();
        em.clear();

        Page<LowStockRow> page = lowStockRepository.findLowStockForOrganization(org.getId(), FIRST_PAGE);

        // A material stocked nowhere holds nothing, which is as far below its level as it goes.
        // An inner join to CurrentStock would drop it and report the organization as healthy.
        assertThat(page.getContent()).extracting(LowStockRow::materialId).containsExactly(bricks.getId());
        assertThat(page.getContent().get(0).currentStock()).isEqualTo(0.0);
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("organization scope reports a material sitting exactly on its reorder level")
    void organizationScope_reportsAMaterialExactlyOnItsLevel() {
        Organization org = persistOrganization("on-the-level");
        Project project = persistProject(org, "Site");
        StorageLocation store = persistLocation(org, "Store");
        Material plywood = persistMaterial(org, "PLY-1", "Plywood", 500.0);
        persistStock(org, plywood, project, store, 500.0);
        em.flush();
        em.clear();

        Page<LowStockRow> page = lowStockRepository.findLowStockForOrganization(org.getId(), FIRST_PAGE);

        assertThat(page.getContent()).extracting(LowStockRow::materialId).containsExactly(plywood.getId());
    }

    @Test
    @DisplayName("organization scope ignores a material with no reorder level set")
    void organizationScope_ignoresAMaterialWithNoReorderLevel() {
        Organization org = persistOrganization("no-level");
        Project project = persistProject(org, "Site");
        StorageLocation store = persistLocation(org, "Store");
        Material wood = persistMaterial(org, "WD-1", "Wood", null);
        persistStock(org, wood, project, store, 5.0);
        em.flush();
        em.clear();

        Page<LowStockRow> page = lowStockRepository.findLowStockForOrganization(org.getId(), FIRST_PAGE);

        // Five units left and nobody said what "low" means for this material, so there is nothing
        // to report. A threshold nobody set is not a threshold that was crossed.
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("organization scope totals every project and location, so a healthy total hides a short site")
    void organizationScope_totalsAcrossProjectsAndLocations() {
        Organization org = persistOrganization("totals");
        Project siteA = persistProject(org, "Site A");
        Project siteB = persistProject(org, "Site B");
        StorageLocation store = persistLocation(org, "Store");
        Material steel = persistMaterial(org, "STL-1", "TNT Steel", 30.0);
        persistStock(org, steel, siteA, store, 59.0);
        persistStock(org, steel, siteB, store, 1.0);
        em.flush();
        em.clear();

        Page<LowStockRow> orgWide = lowStockRepository.findLowStockForOrganization(org.getId(), FIRST_PAGE);
        Page<LowStockRow> onSiteB = lowStockRepository.findLowStockForProject(org.getId(), siteB.getId(), FIRST_PAGE);

        // Sixty units across the organization against a level of thirty, so nothing is reported
        // there. Site B has one unit, and that is what the aggregate figure on the dashboard
        // cannot say. Both answers are correct and they are answers to different questions.
        assertThat(orgWide.getContent()).isEmpty();
        assertThat(onSiteB.getContent()).extracting(LowStockRow::materialId).containsExactly(steel.getId());
        assertThat(onSiteB.getContent().get(0).currentStock()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("organization scope stays inside the tenant")
    void organizationScope_staysInsideTheTenant() {
        Organization mine = persistOrganization("mine");
        Organization theirs = persistOrganization("theirs");
        Material ours = persistMaterial(mine, "OUR-1", "Cement", 100.0);
        persistMaterial(theirs, "THR-1", "Cement", 100.0);
        em.flush();
        em.clear();

        Page<LowStockRow> page = lowStockRepository.findLowStockForOrganization(mine.getId(), FIRST_PAGE);

        assertThat(page.getContent()).extracting(LowStockRow::materialId).containsExactly(ours.getId());
    }

    @Test
    @DisplayName("organization scope orders by the fraction of the level that is missing, not by quantity")
    void organizationScope_ordersByFractionMissingRatherThanQuantity() {
        Organization org = persistOrganization("ordering");
        Project project = persistProject(org, "Site");
        StorageLocation store = persistLocation(org, "Store");

        Material bricks = persistMaterial(org, "BRK-2", "Red Bricks", 25000.0);
        persistStock(org, bricks, project, store, 20000.0);   // 20% missing, 5,000 units
        Material cement = persistMaterial(org, "CEM-2", "Cement", 100.0);
        persistStock(org, cement, project, store, 10.0);      // 90% missing, 90 units
        em.flush();
        em.clear();

        Page<LowStockRow> page = lowStockRepository.findLowStockForOrganization(org.getId(), FIRST_PAGE);

        // Ordering on the absolute shortfall would put the bricks first on the strength of a
        // number measured in bricks. The site is far closer to running out of cement.
        assertThat(page.getContent()).extracting(LowStockRow::materialId)
                .containsExactly(cement.getId(), bricks.getId());
    }

    @Test
    @DisplayName("organization scope counts every match, not just the page")
    void organizationScope_countsEveryMatchAcrossPages() {
        Organization org = persistOrganization("paging");
        for (int i = 0; i < 5; i++) {
            persistMaterial(org, "PG-" + i, "Material " + i, 10.0);
        }
        em.flush();
        em.clear();

        Page<LowStockRow> firstOfFive = lowStockRepository
                .findLowStockForOrganization(org.getId(), PageRequest.of(0, 2));

        // The count is the whole point of the endpoint for the dashboard card, which wants the
        // number and not the rows. A count derived from the page would report two.
        assertThat(firstOfFive.getContent()).hasSize(2);
        assertThat(firstOfFive.getTotalElements()).isEqualTo(5);
    }

    @Test
    @DisplayName("a reorder level of zero sorts as fully depleted rather than dividing by zero")
    void organizationScope_survivesAReorderLevelOfZero() {
        Organization org = persistOrganization("zero-level");
        Project project = persistProject(org, "Site");
        StorageLocation store = persistLocation(org, "Store");

        Material zeroLevel = persistMaterial(org, "ZRO-1", "Sand", 0.0);
        persistStock(org, zeroLevel, project, store, 0.0);
        Material cement = persistMaterial(org, "CEM-7", "Cement", 100.0);
        persistStock(org, cement, project, store, 90.0);   // 10% missing
        em.flush();
        em.clear();

        // The severity ordering divides by the level, and the database raises on a division by
        // zero rather than returning null, so an unguarded expression would turn this into a 500
        // for any tenant that ever typed a zero into the field.
        Page<LowStockRow> page = lowStockRepository.findLowStockForOrganization(org.getId(), FIRST_PAGE);

        assertThat(page.getContent()).extracting(LowStockRow::materialId)
                .containsExactly(zeroLevel.getId(), cement.getId());
    }

    @Test
    @DisplayName("project scope ignores a material the project has never held")
    void projectScope_ignoresAMaterialNeverHeldOnTheProject() {
        Organization org = persistOrganization("never-held");
        Project siteA = persistProject(org, "Site A");
        Project siteB = persistProject(org, "Site B");
        StorageLocation store = persistLocation(org, "Store");

        Material cement = persistMaterial(org, "CEM-3", "Cement", 100.0);
        persistStock(org, cement, siteA, store, 5.0);
        persistMaterial(org, "TILE-3", "Ceramic Tiles", 100.0);   // in the catalogue, held nowhere
        em.flush();
        em.clear();

        Page<LowStockRow> page = lowStockRepository.findLowStockForProject(org.getId(), siteB.getId(), FIRST_PAGE);

        // Site B holds neither. Reporting the whole catalogue as out of stock at every project
        // that never carried it would be a list of everything, which is a list of nothing.
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("storage-location scope applies the location's override in place of the material's level")
    void locationScope_appliesTheLocationOverride() {
        Organization org = persistOrganization("override");
        Project project = persistProject(org, "Site");
        StorageLocation store = persistLocation(org, "Store");
        Material cement = persistMaterial(org, "CEM-4", "Cement", 100.0);
        persistStock(org, cement, project, store, 150.0);
        persistThreshold(org, cement, store, 200.0, 500.0);
        em.flush();
        em.clear();

        Page<LowStockRow> page = lowStockRepository
                .findLowStockAtStorageLocation(org.getId(), project.getId(), store.getId(), FIRST_PAGE);

        // 150 on hand is comfortable against the material's global level of 100 and short against
        // this location's own level of 200. The console's stock-by-location table compares against
        // the global level even here, which is the comparison this scope exists to replace.
        assertThat(page.getContent()).extracting(LowStockRow::materialId).containsExactly(cement.getId());
        assertThat(page.getContent().get(0).reorderLevel()).isEqualTo(200.0);
        assertThat(page.getContent().get(0).moq()).isEqualTo(500.0);
    }

    @Test
    @DisplayName("storage-location scope falls back to the material's level when the override leaves it null")
    void locationScope_fallsBackWhenTheOverrideLeavesTheLevelNull() {
        Organization org = persistOrganization("partial-override");
        Project project = persistProject(org, "Site");
        StorageLocation store = persistLocation(org, "Store");
        Material cement = persistMaterial(org, "CEM-5", "Cement", 100.0);
        persistStock(org, cement, project, store, 80.0);
        persistThreshold(org, cement, store, null, null);   // an override that overrides nothing
        em.flush();
        em.clear();

        Page<LowStockRow> page = lowStockRepository
                .findLowStockAtStorageLocation(org.getId(), project.getId(), store.getId(), FIRST_PAGE);

        assertThat(page.getContent()).extracting(LowStockRow::materialId).containsExactly(cement.getId());
        assertThat(page.getContent().get(0).reorderLevel()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("storage-location scope reads that one location, not the project total")
    void locationScope_readsOnlyThatLocation() {
        Organization org = persistOrganization("one-location");
        Project project = persistProject(org, "Site");
        StorageLocation yard = persistLocation(org, "Yard");
        StorageLocation shed = persistLocation(org, "Shed");
        Material cement = persistMaterial(org, "CEM-6", "Cement", 100.0);
        persistStock(org, cement, project, yard, 500.0);
        persistStock(org, cement, project, shed, 4.0);
        em.flush();
        em.clear();

        Page<LowStockRow> atShed = lowStockRepository
                .findLowStockAtStorageLocation(org.getId(), project.getId(), shed.getId(), FIRST_PAGE);
        Page<LowStockRow> atYard = lowStockRepository
                .findLowStockAtStorageLocation(org.getId(), project.getId(), yard.getId(), FIRST_PAGE);

        assertThat(atShed.getContent()).extracting(LowStockRow::currentStock).containsExactly(4.0);
        assertThat(atYard.getContent()).isEmpty();
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName("Low stock " + name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name + "@example.test");
        org.setOrganizationPhone("0000000000");
        em.persist(org);
        return org;
    }

    private Project persistProject(Organization org, String name) {
        Project project = new Project();
        project.setProjectName(name);
        project.setProjectAddress(name + " road");
        project.setOrganization(org);
        em.persist(project);
        return project;
    }

    private StorageLocation persistLocation(Organization org, String name) {
        StorageLocation location = new StorageLocation();
        location.setLocationName(name);
        location.setLocationType(StorageLocationType.WAREHOUSE);
        location.setOrganization(org);
        em.persist(location);
        return location;
    }

    private Material persistMaterial(Organization org, String sku, String name, Double reorderLevel) {
        Material material = new Material();
        material.setSku(sku);
        material.setMaterialName(name);
        material.setUnit("units");
        material.setOrganization(org);
        material.setReorderLevel(reorderLevel);
        em.persist(material);
        return material;
    }

    private void persistStock(Organization org, Material material, Project project,
                              StorageLocation location, Double quantity) {
        CurrentStock stock = new CurrentStock();
        stock.setOrganization(org);
        stock.setMaterial(material);
        stock.setProject(project);
        stock.setStorageLocation(location);
        stock.setCurrentQuantity(quantity);
        stock.setStockValue(BigDecimal.ZERO);
        em.persist(stock);
    }

    private void persistThreshold(Organization org, Material material, StorageLocation location,
                                  Double reorderLevel, Double moq) {
        MaterialLocationThreshold threshold = new MaterialLocationThreshold();
        threshold.setOrganization(org);
        threshold.setMaterial(material);
        threshold.setStorageLocation(location);
        threshold.setReorderLevel(reorderLevel);
        threshold.setMoq(moq);
        em.persist(threshold);
    }
}
