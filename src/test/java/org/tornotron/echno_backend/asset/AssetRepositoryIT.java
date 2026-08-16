package org.tornotron.echno_backend.asset;

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
}
