package org.tornotron.echno_backend.material.summary;

import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.tornotron.echno_backend.inventoryTransaction.CurrentStock;
import org.tornotron.echno_backend.inventoryTransaction.CurrentStockRepository;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.enums.StorageLocationType;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The organization-wide aggregates against a real CockroachDB.
 *
 * <p>These are money figures totalled over rows nobody looked at, which makes them the shape of
 * read where a mistake is least visible: a total that quietly leaves out a third of the catalogue,
 * or quietly takes in another tenant's, is still a number, and the person quoting it cannot tell.
 * So the cases here are the ones where a wrong answer would still look like an answer.
 *
 * <h2>Two organizations, on purpose</h2>
 *
 * <p>Every aggregate is checked with a second organization holding stock alongside, because that
 * is the only way a cross-tenant sum shows up at all. Two of the tests go further and enable the
 * Hibernate {@code orgFilter} for the wrong organization while asking for the right one: the
 * queries carry their own organization predicate, so those two are what prove the filter clause
 * genuinely reaches an aggregate query rather than only an entity one. Without it they would come
 * back with the figure the predicate alone selects, which is the whole point of running them.
 *
 * <p>Runs on the plain {@code @DataJpaTest} configuration the other material repository tests use,
 * so it shares their Spring context rather than adding one to a cache the suite is tight on.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MaterialStockSummaryIT extends AbstractIntegrationTest {

    @Autowired
    private MaterialRepository materialRepository;

    @Autowired
    private CurrentStockRepository currentStockRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    @DisplayName("the value total leaves out another organization's stock")
    void valueTotal_excludesAnotherOrganizationsStock() {
        Organization ours = persistOrganization("value-ours");
        Organization theirs = persistOrganization("value-theirs");
        holding(ours, "Cement", "bags", 100.0, new BigDecimal("40000.00"));
        holding(ours, "Steel", "kg", 2000.0, new BigDecimal("150000.00"));
        holding(theirs, "Sand", "cft", 500.0, new BigDecimal("999999.00"));
        em.flush();
        em.clear();

        BigDecimal total = currentStockRepository.sumStockValueForOrganization(ours.getId());

        // A sum is the one read where a leaked row does not look like a leak. Drop the
        // organization predicate and this returns 1,189,999 and reads as our inventory.
        assertThat(total).isEqualByComparingTo("190000.00");
    }

    @Test
    @DisplayName("the value total runs under the tenant filter, not only its own predicate")
    void valueTotal_runsUnderTheTenantFilter() {
        Organization ours = persistOrganization("filter-value-ours");
        Organization theirs = persistOrganization("filter-value-theirs");
        holding(ours, "Cement", "bags", 100.0, new BigDecimal("40000.00"));
        em.flush();
        em.clear();

        // Ask for our organization while the session filter is set to somebody else's. The two
        // scopings intersect to nothing, so a zero here is only possible if the filter clause was
        // actually appended to the aggregate. If Hibernate applied filters to entity queries but
        // not to this one, the query's own predicate would still hand back our 40,000.
        enableOrgFilterFor(theirs);

        assertThat(currentStockRepository.sumStockValueForOrganization(ours.getId()))
                .isEqualByComparingTo("0");
        assertThat(currentStockRepository.countUnvaluedHoldingsForOrganization(ours.getId()))
                .isZero();
    }

    @Test
    @DisplayName("the catalogue count and unit count leave out another organization's materials")
    void catalogueCounts_excludeAnotherOrganization() {
        Organization ours = persistOrganization("catalogue-ours");
        Organization theirs = persistOrganization("catalogue-theirs");
        persistMaterial(ours, "Cement", "bags");
        persistMaterial(ours, "Fly ash", "bags");
        persistMaterial(ours, "Steel", "kg");
        persistMaterial(theirs, "Sand", "cft");
        persistMaterial(theirs, "Paint", "litre");
        em.flush();
        em.clear();

        assertThat(materialRepository.countForOrganization(ours.getId())).isEqualTo(3);
        // Three materials, two units. A count that forgot the DISTINCT would say three, and the
        // tile is captioned "Unique Units".
        assertThat(materialRepository.countDistinctUnitsForOrganization(ours.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("the catalogue count runs under the tenant filter, not only its own predicate")
    void catalogueCount_runsUnderTheTenantFilter() {
        Organization ours = persistOrganization("filter-catalogue-ours");
        Organization theirs = persistOrganization("filter-catalogue-theirs");
        persistMaterial(ours, "Cement", "bags");
        em.flush();
        em.clear();

        enableOrgFilterFor(theirs);

        assertThat(materialRepository.countForOrganization(ours.getId())).isZero();
        assertThat(materialRepository.countDistinctUnitsForOrganization(ours.getId())).isZero();
    }

    @Test
    @DisplayName("stock received with no unit cost is totalled at the zero it holds and reported")
    void unpricedStock_isCountedAtZeroAndReported() {
        Organization org = persistOrganization("unpriced");
        holding(org, "Cement", "bags", 100.0, new BigDecimal("40000.00"));
        // Received with no unit cost, so the posting path added the quantity and no value. The
        // zero was written there, not decided here.
        holding(org, "Rubble", "cft", 250.0, BigDecimal.ZERO);
        em.flush();
        em.clear();

        assertThat(currentStockRepository.sumStockValueForOrganization(org.getId()))
                .isEqualByComparingTo("40000.00");
        // The total understates by whatever the rubble is worth, and this is what says so. Without
        // it the figure is complete-looking and short, which is the failure this endpoint replaces.
        assertThat(currentStockRepository.countUnvaluedHoldingsForOrganization(org.getId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a holding that has run down to nothing is not reported as unpriced")
    void emptyHolding_isNotReportedAsUnpriced() {
        Organization org = persistOrganization("run-down");
        // Quantity zero and value zero: the location has run out, which is a complete and correct
        // answer. Counting it as unpriced would caption an accurate total as an understatement,
        // and every seeded balance row starts in exactly this state.
        holding(org, "Cement", "bags", 0.0, BigDecimal.ZERO);
        em.flush();
        em.clear();

        assertThat(currentStockRepository.countUnvaluedHoldingsForOrganization(org.getId()))
                .isZero();
    }

    @Test
    @DisplayName("project scope covers what the project carries, not the whole catalogue")
    void projectScope_coversWhatTheProjectCarries() {
        Organization org = persistOrganization("project-scope");
        Project site = persistProject(org, "Site A");
        Project other = persistProject(org, "Site B");
        StorageLocation store = persistLocation(org, "Store");

        Material cement = persistMaterial(org, "Cement", "bags");
        Material flyAsh = persistMaterial(org, "Fly ash", "bags");
        Material steel = persistMaterial(org, "Steel", "kg");
        persistMaterial(org, "Never stocked", "nos");

        persistStock(org, cement, site, store, 100.0, new BigDecimal("40000.00"));
        persistStock(org, flyAsh, site, store, 60.0, new BigDecimal("12000.00"));
        persistStock(org, steel, other, store, 2000.0, new BigDecimal("150000.00"));
        em.flush();
        em.clear();

        assertThat(currentStockRepository.sumStockValueForProject(org.getId(), site.getId()))
                .isEqualByComparingTo("52000.00");
        // Two materials at this site, both in bags. The organization holds four materials in
        // three units, which is the figure a project view must not be shown.
        assertThat(currentStockRepository.countDistinctMaterialsForProject(org.getId(), site.getId()))
                .isEqualTo(2);
        assertThat(currentStockRepository.countDistinctUnitsForProject(org.getId(), site.getId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("project scope leaves out another organization even when handed its project id")
    void projectScope_excludesAnotherOrganization() {
        Organization ours = persistOrganization("project-ours");
        Organization theirs = persistOrganization("project-theirs");
        Project theirSite = persistProject(theirs, "Their site");
        StorageLocation theirStore = persistLocation(theirs, "Their store");
        Material sand = persistMaterial(theirs, "Sand", "cft");
        persistStock(theirs, sand, theirSite, theirStore, 500.0, new BigDecimal("999999.00"));
        em.flush();
        em.clear();

        // Project ids are unique across the installation, so nothing but the organization
        // predicate stands between a guessed id and another tenant's site total.
        assertThat(currentStockRepository.sumStockValueForProject(ours.getId(), theirSite.getId()))
                .isEqualByComparingTo("0");
        assertThat(currentStockRepository.countDistinctMaterialsForProject(ours.getId(), theirSite.getId()))
                .isZero();
        assertThat(currentStockRepository.countDistinctUnitsForProject(ours.getId(), theirSite.getId()))
                .isZero();
        assertThat(currentStockRepository.countUnvaluedHoldingsForProject(ours.getId(), theirSite.getId()))
                .isZero();
    }

    @Test
    @DisplayName("an organization holding nothing totals to zero rather than to null")
    void emptyOrganization_totalsToZero() {
        Organization org = persistOrganization("empty");
        em.flush();
        em.clear();

        // SUM over no rows is null in SQL, and a null money figure reaching the console is a
        // blank tile for a reason nobody can diagnose.
        assertThat(currentStockRepository.sumStockValueForOrganization(org.getId()))
                .isEqualByComparingTo("0");
        assertThat(materialRepository.countForOrganization(org.getId())).isZero();
        assertThat(materialRepository.countDistinctUnitsForOrganization(org.getId())).isZero();
    }

    private void enableOrgFilterFor(Organization org) {
        em.getEntityManager().unwrap(Session.class)
                .enableFilter("orgFilter")
                .setParameter("organizationId", org.getId());
    }

    /** A material with one balance row against a project and location of its own. */
    private void holding(Organization org, String name, String unit, Double quantity, BigDecimal value) {
        Material material = persistMaterial(org, name, unit);
        Project project = persistProject(org, name + " site");
        StorageLocation location = persistLocation(org, name + " store");
        persistStock(org, material, project, location, quantity, value);
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName("Stock summary " + name);
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

    private Material persistMaterial(Organization org, String name, String unit) {
        Material material = new Material();
        material.setMaterialName(name);
        material.setUnit(unit);
        material.setOrganization(org);
        em.persist(material);
        return material;
    }

    private void persistStock(Organization org, Material material, Project project,
                              StorageLocation location, Double quantity, BigDecimal value) {
        CurrentStock stock = new CurrentStock();
        stock.setOrganization(org);
        stock.setMaterial(material);
        stock.setProject(project);
        stock.setStorageLocation(location);
        stock.setCurrentQuantity(quantity);
        stock.setStockValue(value);
        em.persist(stock);
    }
}
