package org.tornotron.echno_backend.asset;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.repository.AttachmentRepository;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessFilter.Links;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.vendor.Vendor;

/**
 * The register's queries admit exactly the assets {@link AccessFilter#admits} does, on CockroachDB, so a
 * list and a check on one asset can never disagree. The expiring documents follow the same assets.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AssetAccessFilterIT extends AbstractIntegrationTest {

    @Autowired
    private EntityManager em;

    @Autowired
    private AssetRepository assets;

    @Autowired
    private AttachmentRepository attachments;

    private Organization mine;
    private Project first;
    private Project second;
    private Employee custodian;
    private Vendor vendor;
    private List<Asset> all;

    @BeforeEach
    void aRegister() {
        AccessFixtures fixtures = new AccessFixtures(em);
        mine = fixtures.organization("Filter " + System.nanoTime());
        Organization theirs = fixtures.organization("Filter other " + System.nanoTime());
        first = fixtures.project(mine, "First");
        second = fixtures.project(mine, "Second");
        Project theirProject = fixtures.project(theirs, "Theirs");
        custodian = fixtures.employee(mine, "custodian-" + System.nanoTime());
        vendor = fixtures.vendor(mine, "Supplier " + System.nanoTime());
        all = List.of(
                asset(mine, "On first", first, null, null),
                asset(mine, "On second", second, null, null),
                asset(mine, "With custodian on second", second, custodian.getId(), null),
                asset(mine, "Supplied, in the yard", null, null, vendor),
                asset(mine, "Supplied, on second", second, null, vendor),
                asset(mine, "In the yard", null, null, null),
                // Another organization's asset, linked to the same people and project ids it could be confused with.
                asset(theirs, "Theirs", theirProject, custodian.getId(), null));
        all.forEach(asset -> document(asset));
        em.flush();
        em.clear();
    }

    @Test
    void everyKindOfFilterAdmitsTheSameAssetsInTheQueryAsInMemory() {
        Long org = mine.getId();
        Map<String, AccessFilter> filters = Map.of(
                "all", AccessFilter.all(org),
                "none", AccessFilter.none(org),
                "one project", new AccessFilter(org, false, Set.of(first.getId()), null, null),
                "their custodian", new AccessFilter(org, false, null, related(), null),
                "their vendor", new AccessFilter(org, false, null, supplied(), null),
                "custodian on one project", new AccessFilter(org, false, null, null, Map.of(second.getId(), related())),
                "vendor on one project", new AccessFilter(org, false, null, null, Map.of(first.getId(), supplied())),
                "an owner, which no asset has", new AccessFilter(org, false, null,
                        new Links(Set.of(custodian.getId()), null, null, null), null),
                "a mix", new AccessFilter(org, false, Set.of(first.getId()), supplied(),
                        Map.of(second.getId(), related())));

        filters.forEach((name, filter) -> {
            Set<String> expected = all.stream()
                    .filter(asset -> filter.admits(AssetAccess.facts(asset)))
                    .map(Asset::getName)
                    .collect(Collectors.toSet());
            Set<String> listed = assets.findAll(AssetAccess.admittedBy(filter), PageRequest.of(0, 50)).stream()
                    .map(Asset::getName)
                    .collect(Collectors.toSet());
            Set<String> documented = attachments.findAll(AssetService.onVisibleAssets(filter)).stream()
                    .map(Attachment::getOriginalFilename)
                    .collect(Collectors.toSet());

            assertThat(listed).as(name).isEqualTo(expected);
            assertThat(documented).as(name).isEqualTo(expected);
            assertThat(listed).as(name).doesNotContain("Theirs");
        });
    }

    @Test
    void aFilterThatAdmitsSomethingIsNotTrivial() {
        // Guards the test above from passing on filters that all happen to admit nothing.
        AccessFilter mix = new AccessFilter(mine.getId(), false, Set.of(first.getId()), supplied(),
                Map.of(second.getId(), related()));
        assertThat(all.stream().filter(asset -> mix.admits(AssetAccess.facts(asset))).map(Asset::getName))
                .containsExactlyInAnyOrder("On first", "With custodian on second", "Supplied, in the yard",
                        "Supplied, on second");
    }

    private Links related() {
        return new Links(null, Set.of(custodian.getId()), null, null);
    }

    private Links supplied() {
        return new Links(null, null, null, Set.of(vendor.getId()));
    }

    private Asset asset(Organization org, String name, Project project, Long custodianId, Vendor supplier) {
        Asset asset = new Asset();
        asset.setOrganization(org);
        asset.setName(name);
        asset.setStatus("available");
        asset.setAssignedProject(project);
        asset.setAssignedToId(custodianId);
        asset.setVendor(supplier);
        em.persist(asset);
        return asset;
    }

    /** One expiring document per asset, named after it. */
    private void document(Asset asset) {
        Attachment attachment = new Attachment();
        attachment.setEntityType(AssetDocuments.ENTITY_TYPE);
        attachment.setEntityId(asset.getId());
        attachment.setStorageKey("assets/" + asset.getId());
        attachment.setOriginalFilename(asset.getName());
        attachment.setExpiresOn(LocalDate.now());
        attachment.setOrganization(asset.getOrganization());
        em.persist(attachment);
    }
}
