package org.tornotron.echno_backend.material.threshold;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.storageLocation.StorageLocation;
import org.tornotron.echno_backend.storageLocation.enums.StorageLocationType;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link MaterialLocationThresholdRepository} against a real CockroachDB
 * (see {@link AbstractIntegrationTest}). Asserts that a persisted per-location override is
 * retrievable by material and location, that listing an override by material stays scoped to the
 * material and tenant, and that deleting one removes it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MaterialLocationThresholdRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private MaterialLocationThresholdRepository thresholdRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void findByMaterialAndLocation_returnsThePersistedOverride() {
        Organization org = persistOrganization("Org A");
        Material material = persistMaterial(org, "CEM-001", "Cement");
        StorageLocation location = persistLocation(org, "Main Store");
        persistThreshold(org, material, location, 150.0, 1500.0);
        em.flush();
        em.clear();

        Optional<MaterialLocationThreshold> found = thresholdRepository
                .findByMaterial_IdAndStorageLocation_IdAndOrganization_Id(
                        material.getId(), location.getId(), org.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getMinStock()).isEqualTo(150.0);
        assertThat(found.get().getMaxStock()).isEqualTo(1500.0);
        assertThat(found.get().getMaterial().getId()).isEqualTo(material.getId());
        assertThat(found.get().getStorageLocation().getLocationName()).isEqualTo("Main Store");
        assertThat(found.get().getOrganization().getId()).isEqualTo(org.getId());
    }

    @Test
    void findByMaterial_listsOnlyThatMaterialsOverridesInTheTenant() {
        Organization orgA = persistOrganization("Org A2");
        Organization orgB = persistOrganization("Org B2");

        Material m1 = persistMaterial(orgA, "M1", "Cement");
        Material m2 = persistMaterial(orgA, "M2", "Sand");
        Material otherTenantMaterial = persistMaterial(orgB, "M3", "Steel");

        StorageLocation l1 = persistLocation(orgA, "Store 1");
        StorageLocation l2 = persistLocation(orgA, "Store 2");
        StorageLocation lb = persistLocation(orgB, "Store B");

        persistThreshold(orgA, m1, l1, 10.0, 100.0);
        persistThreshold(orgA, m1, l2, 20.0, 200.0);
        persistThreshold(orgA, m2, l1, 30.0, 300.0);            // different material, same tenant
        persistThreshold(orgB, otherTenantMaterial, lb, 40.0, 400.0); // different tenant
        em.flush();
        em.clear();

        List<MaterialLocationThreshold> overrides =
                thresholdRepository.findByMaterial_IdAndOrganization_Id(m1.getId(), orgA.getId());

        assertThat(overrides).hasSize(2);
        assertThat(overrides).allMatch(o -> o.getMaterial().getId().equals(m1.getId()));
        assertThat(overrides).allMatch(o -> o.getOrganization().getId().equals(orgA.getId()));
        assertThat(overrides).extracting(o -> o.getStorageLocation().getLocationName())
                .containsExactlyInAnyOrder("Store 1", "Store 2");
    }

    @Test
    void delete_removesTheOverride() {
        Organization org = persistOrganization("Org C");
        Material material = persistMaterial(org, "CEM-C", "Cement");
        StorageLocation location = persistLocation(org, "Yard");
        MaterialLocationThreshold threshold = persistThreshold(org, material, location, 5.0, 50.0);
        em.flush();

        thresholdRepository.delete(threshold);
        em.flush();
        em.clear();

        Optional<MaterialLocationThreshold> found = thresholdRepository
                .findByMaterial_IdAndStorageLocation_IdAndOrganization_Id(
                        material.getId(), location.getId(), org.getId());

        assertThat(found).isEmpty();
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

    private Material persistMaterial(Organization org, String sku, String name) {
        Material material = new Material();
        material.setSku(sku);
        material.setMaterialName(name);
        material.setUnit("bags");
        material.setOrganization(org);
        em.persist(material);
        return material;
    }

    private StorageLocation persistLocation(Organization org, String name) {
        StorageLocation location = new StorageLocation();
        location.setLocationName(name);
        location.setLocationType(StorageLocationType.WAREHOUSE);
        location.setOrganization(org);
        em.persist(location);
        return location;
    }

    private MaterialLocationThreshold persistThreshold(Organization org, Material material,
                                                       StorageLocation location, Double minStock, Double maxStock) {
        MaterialLocationThreshold threshold = new MaterialLocationThreshold();
        threshold.setOrganization(org);
        threshold.setMaterial(material);
        threshold.setStorageLocation(location);
        threshold.setMinStock(minStock);
        threshold.setMaxStock(maxStock);
        em.persist(threshold);
        return threshold;
    }
}
