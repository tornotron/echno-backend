package org.tornotron.echno_backend.materialConsumption;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
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
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.materialConsumption.enums.MaterialConsumptionType;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessFilter.Links;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * The consumption query admits exactly the consumptions {@link AccessFilter#admits} does, on
 * CockroachDB, so a list and a check on one consumption can never disagree.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MaterialConsumptionAccessFilterIT extends AbstractIntegrationTest {

    @Autowired
    private EntityManager em;

    @Autowired
    private MaterialConsumptionRepository consumptions;

    private Organization mine;
    private Project first;
    private Project second;
    private Employee creator;
    private List<MaterialConsumption> all;

    @BeforeEach
    void someConsumptions() {
        AccessFixtures fixtures = new AccessFixtures(em);
        mine = fixtures.organization("Filter " + System.nanoTime());
        Organization theirs = fixtures.organization("Filter other " + System.nanoTime());
        first = fixtures.project(mine, "First");
        second = fixtures.project(mine, "Second");
        Project theirProject = fixtures.project(theirs, "Theirs");
        creator = fixtures.employee(mine, "creator-" + System.nanoTime());
        Employee someoneElse = fixtures.employee(mine, "storekeeper-" + System.nanoTime());
        Employee theirCreator = fixtures.employee(theirs, "their-creator-" + System.nanoTime());
        Material cement = material(mine);
        Material theirCement = material(theirs);
        all = List.of(
                consumption(mine, "On first", first, someoneElse, cement),
                consumption(mine, "On second", second, someoneElse, cement),
                consumption(mine, "Recorded by the creator on first", first, creator, cement),
                consumption(mine, "Recorded by the creator on second", second, creator, cement),
                consumption(mine, "Recorded by nobody on second", second, null, cement),
                // Another organization's consumption, on a project a filter of mine could be confused with.
                consumption(theirs, "Theirs", theirProject, theirCreator, theirCement));
        em.flush();
        em.clear();
    }

    @Test
    void everyKindOfFilterAdmitsTheSameConsumptionsInTheQueryAsInMemory() {
        Long org = mine.getId();
        Map<String, AccessFilter> filters = Map.of(
                "all", AccessFilter.all(org),
                "none", AccessFilter.none(org),
                "one project", new AccessFilter(org, false, Set.of(first.getId()), null, null),
                "their own", new AccessFilter(org, false, null, owned(), null),
                "their own on one project", new AccessFilter(org, false, null, null, Map.of(second.getId(), owned())),
                "a related employee, which no consumption has", new AccessFilter(org, false, null,
                        new Links(null, Set.of(creator.getId()), null, null), null),
                "a mix", new AccessFilter(org, false, Set.of(first.getId()), null,
                        Map.of(second.getId(), owned())));

        filters.forEach((name, filter) -> {
            Set<String> expected = all.stream()
                    .filter(consumption -> filter.admits(MaterialConsumptionAccess.facts(consumption)))
                    .map(MaterialConsumption::getDetails)
                    .collect(Collectors.toSet());
            Set<String> listed = consumptions.findAll(MaterialConsumptionAccess.admittedBy(filter), PageRequest.of(0, 50))
                    .stream()
                    .map(MaterialConsumption::getDetails)
                    .collect(Collectors.toSet());

            assertThat(listed).as(name).isEqualTo(expected);
            assertThat(listed).as(name).doesNotContain("Theirs");
        });
    }

    @Test
    void aFilterThatAdmitsSomethingIsNotTrivial() {
        // Guards the test above from passing on filters that all happen to admit nothing.
        AccessFilter mix = new AccessFilter(mine.getId(), false, Set.of(first.getId()), null,
                Map.of(second.getId(), owned()));
        assertThat(all.stream().filter(consumption -> mix.admits(MaterialConsumptionAccess.facts(consumption)))
                .map(MaterialConsumption::getDetails))
                .containsExactlyInAnyOrder("On first", "Recorded by the creator on first",
                        "Recorded by the creator on second");
    }

    private Links owned() {
        return new Links(Set.of(creator.getId()), null, null, null);
    }

    private Material material(Organization org) {
        Material material = new Material();
        material.setMaterialName("Cement " + System.nanoTime());
        material.setUnit("bag");
        material.setOrganization(org);
        em.persist(material);
        return material;
    }

    /** A consumption named by its details. */
    private MaterialConsumption consumption(Organization org, String name, Project project, Employee createdBy,
                                            Material material) {
        MaterialConsumption consumption = new MaterialConsumption();
        consumption.setConsumptionDate(LocalDateTime.of(2026, 11, 1, 10, 0));
        consumption.setMaterial(material);
        consumption.setQuantity(5);
        consumption.setConsumptionType(MaterialConsumptionType.USED_FROM_STOCK);
        consumption.setDetails(name);
        consumption.setCreatedBy(createdBy);
        consumption.setProject(project);
        consumption.setOrganization(org);
        em.persist(consumption);
        return consumption;
    }
}
