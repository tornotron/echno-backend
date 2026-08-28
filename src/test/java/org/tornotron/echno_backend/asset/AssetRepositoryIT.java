package org.tornotron.echno_backend.asset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link AssetRepository} against a real CockroachDB
 * (see {@link AbstractIntegrationTest}). Asserts the tenant-scoped lookup and
 * that a persisted asset shows up in a paginated {@code findAll}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AssetRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void findByIdAndOrganization_returnsTheAssetForItsOrganization() {
        Organization org = persistOrganization("Org A");
        Asset asset = persistAsset(org, "Excavator");
        em.flush();
        em.clear();

        Optional<Asset> found = assetRepository.findByIdAndOrganization_Id(asset.getId(), org.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Excavator");
        assertThat(found.get().getOrganization().getId()).isEqualTo(org.getId());
    }

    @Test
    void findAll_paginated_includesThePersistedAsset() {
        Organization org = persistOrganization("Org B");
        persistAsset(org, "Concrete Mixer");
        em.flush();
        em.clear();

        Page<Asset> result = assetRepository.findAll(PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Asset::getName)
                .contains("Concrete Mixer");
    }

    @Test
    void newAsset_storesAssignedToIdAlongsideName() {
        Organization org = persistOrganization("Org C");
        Employee ravi = persistEmployee(org, "Ravi Kumar");

        Asset asset = persistAsset(org, "Tower Crane");
        asset.setAssignedTo(ravi.getEmployeeName());
        asset.setAssignedToId(ravi.getId());
        em.flush();
        em.clear();

        Asset reloaded = assetRepository.findById(asset.getId()).orElseThrow();
        assertThat(reloaded.getAssignedTo()).isEqualTo("Ravi Kumar");
        assertThat(reloaded.getAssignedToId()).isEqualTo(ravi.getId());
    }

    @Test
    void backfill_resolvesUniqueNameToEmployeeId() {
        Organization org = persistOrganization("Org D");
        Employee ravi = persistEmployee(org, "Ravi Kumar");

        Asset asset = persistAsset(org, "Excavator");
        asset.setAssignedTo("Ravi Kumar");
        asset.setAssignedToId(null);
        em.flush();

        runAssignedToBackfill();
        em.clear();

        Asset reloaded = assetRepository.findById(asset.getId()).orElseThrow();
        assertThat(reloaded.getAssignedToId()).isEqualTo(ravi.getId());
    }

    @Test
    void backfill_leavesAmbiguousNameNull() {
        Organization org = persistOrganization("Org E");
        // Two employees share the name in the same organization: the match is ambiguous.
        persistEmployee(org, "Ravi Kumar");
        persistEmployee(org, "Ravi Kumar");

        Asset asset = persistAsset(org, "Loader");
        asset.setAssignedTo("Ravi Kumar");
        asset.setAssignedToId(null);
        em.flush();

        runAssignedToBackfill();
        em.clear();

        Asset reloaded = assetRepository.findById(asset.getId()).orElseThrow();
        assertThat(reloaded.getAssignedToId()).isNull();
    }

    @Test
    void assignedProjectBackfill_resolvesAUniqueNameToTheProject() {
        Organization org = persistOrganization("Org F");
        Project marina = persistProject(org, "Marina Heights Towers");
        Asset asset = persistAssetWithLegacyProject(org, "Excavator", "Marina Heights Towers");

        runAssignedProjectBackfill();
        em.clear();

        Asset reloaded = assetRepository.findById(asset.getId()).orElseThrow();
        assertThat(reloaded.getAssignedProject().getId()).isEqualTo(marina.getId());
        // The text is kept even where it resolved, which is what makes the migration reversible.
        assertThat(reloaded.getLegacyAssignedProject()).isEqualTo("Marina Heights Towers");
    }

    @Test
    void assignedProjectBackfill_matchesPastCaseAndSurroundingSpace() {
        Organization org = persistOrganization("Org G");
        Project silverOak = persistProject(org, "Silver Oak Residences");
        Asset asset = persistAssetWithLegacyProject(org, "Loader", "  silver oak residences ");

        runAssignedProjectBackfill();
        em.clear();

        assertThat(assetRepository.findById(asset.getId()).orElseThrow().getAssignedProject().getId())
                .isEqualTo(silverOak.getId());
    }

    @Test
    void assignedProjectBackfill_leavesTextThatNamesNoProjectUnresolvedAndKeepsIt() {
        Organization org = persistOrganization("Org H");
        persistProject(org, "Marina Heights Towers");
        Asset asset = persistAssetWithLegacyProject(org, "Concrete Pump", "Marina Hts - phase 2 (old sheet)");

        runAssignedProjectBackfill();
        em.clear();

        Asset reloaded = assetRepository.findById(asset.getId()).orElseThrow();
        assertThat(reloaded.getAssignedProject()).isNull();
        // Nothing is dropped: the unresolved text is still there to be read and corrected by hand.
        assertThat(reloaded.getLegacyAssignedProject()).isEqualTo("Marina Hts - phase 2 (old sheet)");
    }

    @Test
    void assignedProjectBackfill_leavesAnAmbiguousNameUnresolved() {
        Organization org = persistOrganization("Org I");
        // Two projects share the name in one organization: a guess would be worse than nothing.
        persistProject(org, "Phase 1");
        persistProject(org, "Phase 1");
        Asset asset = persistAssetWithLegacyProject(org, "Tower Crane", "Phase 1");

        runAssignedProjectBackfill();
        em.clear();

        Asset reloaded = assetRepository.findById(asset.getId()).orElseThrow();
        assertThat(reloaded.getAssignedProject()).isNull();
        assertThat(reloaded.getLegacyAssignedProject()).isEqualTo("Phase 1");
    }

    @Test
    void assignedProjectBackfill_doesNotMatchAcrossOrganizations() {
        Organization mine = persistOrganization("Org J");
        Organization theirs = persistOrganization("Org K");
        persistProject(theirs, "Shared Name Site");
        Asset asset = persistAssetWithLegacyProject(mine, "Roller", "Shared Name Site");

        runAssignedProjectBackfill();
        em.clear();

        assertThat(assetRepository.findById(asset.getId()).orElseThrow().getAssignedProject()).isNull();
    }

    @Test
    void openingEntrySeed_carriesUnresolvedFreeTextIntoTheLedgerRatherThanLosingIt() {
        Organization org = persistOrganization("Org L");
        Asset asset = persistAssetWithLegacyProject(org, "Grader", "Marina Hts - phase 2 (old sheet)");

        runAssignedProjectBackfill();
        runOpeningEntrySeed();
        em.clear();

        Object[] entry = (Object[]) em.getEntityManager().createNativeQuery(
                        "SELECT movement_type, to_project_id, to_project_name FROM asset_movement "
                                + "WHERE asset_id = :id")
                .setParameter("id", asset.getId())
                .getSingleResult();
        assertThat(entry[0]).isEqualTo("REGISTRATION");
        assertThat(entry[1]).isNull();
        // The text that matched no project is what the ledger records the asset as having been on.
        assertThat(entry[2]).isEqualTo("Marina Hts - phase 2 (old sheet)");
    }

    @Test
    void openingEntrySeed_skipsAnAssetThatIsNowhereRatherThanInventingAnEntry() {
        Organization org = persistOrganization("Org M");
        Asset asset = persistAsset(org, "Spare Compressor");
        em.flush();

        runOpeningEntrySeed();
        em.clear();

        Object count = em.getEntityManager().createNativeQuery(
                        "SELECT count(*) FROM asset_movement WHERE asset_id = :id")
                .setParameter("id", asset.getId())
                .getSingleResult();
        assertThat(((Number) count).longValue()).isZero();
    }

    /** Runs the SQL from changeset 064-seed-asset-movement-opening-entries. */
    private void runOpeningEntrySeed() {
        em.getEntityManager().createNativeQuery(
                "INSERT INTO asset_movement ("
                        + "  asset_id, organization_id, movement_type,"
                        + "  to_project_id, to_project_name,"
                        + "  to_location_id, to_location_name,"
                        + "  to_assigned_to_id, to_assigned_to,"
                        + "  moved_at, recorded_at, reason) "
                        + "SELECT a.id, a.organization_id, 'REGISTRATION', a.assigned_project_id,"
                        + "       COALESCE(p.project_name, NULLIF(btrim(a.assigned_project), '')),"
                        + "       a.location_id, l.location_name, a.assigned_to_id, a.assigned_to,"
                        + "       COALESCE(a.created_at, CURRENT_TIMESTAMP), CURRENT_TIMESTAMP,"
                        + "       'Opening entry recorded when the asset movement ledger was introduced.' "
                        + "FROM asset a "
                        + "LEFT JOIN project p ON p.id = a.assigned_project_id "
                        + "LEFT JOIN storage_location l ON l.id = a.location_id "
                        + "WHERE (a.assigned_project_id IS NOT NULL"
                        + "   OR NULLIF(btrim(COALESCE(a.assigned_project, '')), '') IS NOT NULL"
                        + "   OR a.location_id IS NOT NULL"
                        + "   OR a.assigned_to_id IS NOT NULL"
                        + "   OR NULLIF(btrim(COALESCE(a.assigned_to, '')), '') IS NOT NULL) "
                        + "  AND NOT EXISTS (SELECT 1 FROM asset_movement m WHERE m.asset_id = a.id)")
                .executeUpdate();
    }

    /**
     * Runs the SQL from changeset 063-backfill-asset-assigned-project-id against the test
     * database, so the match rules are exercised as real CockroachDB rather than argued about.
     */
    private void runAssignedProjectBackfill() {
        em.getEntityManager().createNativeQuery(
                "UPDATE asset "
                        + "SET assigned_project_id = m.project_id "
                        + "FROM ("
                        + "  SELECT p.organization_id AS organization_id,"
                        + "         lower(btrim(p.project_name)) AS match_key,"
                        + "         min(p.id) AS project_id,"
                        + "         count(*) AS candidates"
                        + "  FROM project p"
                        + "  WHERE p.project_name IS NOT NULL AND btrim(p.project_name) <> ''"
                        + "  GROUP BY p.organization_id, lower(btrim(p.project_name))"
                        + ") AS m "
                        + "WHERE asset.assigned_project IS NOT NULL "
                        + "  AND btrim(asset.assigned_project) <> '' "
                        + "  AND asset.assigned_project_id IS NULL "
                        + "  AND asset.organization_id = m.organization_id "
                        + "  AND lower(btrim(asset.assigned_project)) = m.match_key "
                        + "  AND m.candidates = 1")
                .executeUpdate();
    }

    private Project persistProject(Organization org, String name) {
        Project project = new Project();
        project.setOrganization(org);
        project.setProjectName(name);
        em.persist(project);
        return project;
    }

    /**
     * Persists an asset carrying the free text the reference migration has to deal with. The
     * column is mapped read-only on the entity now, which is the point, so the text goes in the
     * way it is already there in a live database: with SQL.
     */
    private Asset persistAssetWithLegacyProject(Organization org, String name, String legacyProject) {
        Asset asset = persistAsset(org, name);
        em.flush();
        em.getEntityManager()
                .createNativeQuery("UPDATE asset SET assigned_project = :text WHERE id = :id")
                .setParameter("text", legacyProject)
                .setParameter("id", asset.getId())
                .executeUpdate();
        return asset;
    }

    /** Runs the SQL from changeset 041-backfill-asset-assigned-to-id against the test database. */
    private void runAssignedToBackfill() {
        em.getEntityManager().createNativeQuery(
                "UPDATE asset a "
                        + "SET assigned_to_id = ("
                        + "  SELECT e.id FROM employee e "
                        + "  WHERE e.employee_name = a.assigned_to "
                        + "    AND e.organization_id = a.organization_id) "
                        + "WHERE a.assigned_to_id IS NULL "
                        + "  AND a.assigned_to IS NOT NULL "
                        + "  AND a.organization_id IS NOT NULL "
                        + "  AND (SELECT COUNT(*) FROM employee e "
                        + "       WHERE e.employee_name = a.assigned_to "
                        + "         AND e.organization_id = a.organization_id) = 1")
                .executeUpdate();
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

    private Asset persistAsset(Organization org, String name) {
        Asset asset = new Asset();
        asset.setOrganization(org);
        asset.setName(name);
        asset.setStatus("available");
        asset.setType("heavy-equipment");
        em.persist(asset);
        return asset;
    }

    private Employee persistEmployee(Organization org, String name) {
        User user = new User();
        user.setKeycloakId("kc-" + name.toLowerCase().replace(" ", "-") + "-" + System.nanoTime());
        user.setName(name);
        em.persist(user);

        Employee employee = new Employee();
        employee.setOrganization(org);
        employee.setUser(user);
        employee.setEmployeeName(name);
        employee.setGender("U");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress(name.toLowerCase().replace(" ", ".") + "-" + System.nanoTime() + "@emp.test");
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        em.persist(employee);
        return employee;
    }
}
