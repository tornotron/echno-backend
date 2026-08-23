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
