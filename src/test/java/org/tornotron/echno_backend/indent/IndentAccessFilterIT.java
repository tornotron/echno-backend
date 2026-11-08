package org.tornotron.echno_backend.indent;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
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
import org.tornotron.echno_backend.indent.enums.IndentStatus;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.indentItem.IndentItemRepository;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessFilter.Links;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * The indent queries admit exactly the indents {@link AccessFilter#admits} does, on CockroachDB, so a
 * list and a check on one indent can never disagree. Their lines follow the same indents.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IndentAccessFilterIT extends AbstractIntegrationTest {

    @Autowired
    private EntityManager em;

    @Autowired
    private IndentRepository indents;

    @Autowired
    private IndentItemRepository lines;

    private Organization mine;
    private Project first;
    private Project second;
    private Employee raiser;
    private List<Indent> all;

    @BeforeEach
    void someIndents() {
        AccessFixtures fixtures = new AccessFixtures(em);
        mine = fixtures.organization("Filter " + System.nanoTime());
        Organization theirs = fixtures.organization("Filter other " + System.nanoTime());
        first = fixtures.project(mine, "First");
        second = fixtures.project(mine, "Second");
        Project theirProject = fixtures.project(theirs, "Theirs");
        raiser = fixtures.employee(mine, "raiser-" + System.nanoTime());
        Employee someoneElse = fixtures.employee(mine, "someone-" + System.nanoTime());
        all = List.of(
                indent(mine, "On first", first, someoneElse),
                indent(mine, "On second", second, someoneElse),
                indent(mine, "Raised by them on first", first, raiser),
                indent(mine, "Raised by them on second", second, raiser),
                indent(mine, "Raised by nobody on second", second, null),
                // Another organization's indent, raised by the same person on a project it could be confused with.
                indent(theirs, "Theirs", theirProject, raiser));
        all.forEach(this::line);
        em.flush();
        em.clear();
    }

    @Test
    void everyKindOfFilterAdmitsTheSameIndentsInTheQueryAsInMemory() {
        Long org = mine.getId();
        Map<String, AccessFilter> filters = Map.of(
                "all", AccessFilter.all(org),
                "none", AccessFilter.none(org),
                "one project", new AccessFilter(org, false, Set.of(first.getId()), null, null),
                "their own", new AccessFilter(org, false, null, own(), null),
                "their own on one project", new AccessFilter(org, false, null, null, Map.of(second.getId(), own())),
                "a related person, which no indent has", new AccessFilter(org, false, null,
                        new Links(null, Set.of(raiser.getId()), null, null), null),
                "a mix", new AccessFilter(org, false, Set.of(first.getId()), null,
                        Map.of(second.getId(), own())));

        filters.forEach((name, filter) -> {
            Set<String> expected = all.stream()
                    .filter(indent -> filter.admits(IndentAccess.facts(indent)))
                    .map(Indent::getRemarks)
                    .collect(Collectors.toSet());
            Set<String> listed = indents.findAll(IndentAccess.admittedBy(filter), PageRequest.of(0, 50)).stream()
                    .map(Indent::getRemarks)
                    .collect(Collectors.toSet());
            long counted = indents.findAll(IndentAccess.admittedBy(filter), PageRequest.of(0, 1)).getTotalElements();
            Set<String> lined = lines.findAll(IndentAccess.linesAdmittedBy(filter)).stream()
                    .map(IndentItem::getRemarks)
                    .collect(Collectors.toSet());

            assertThat(listed).as(name).isEqualTo(expected);
            assertThat(counted).as(name).isEqualTo(expected.size());
            assertThat(lined).as(name).isEqualTo(expected);
            assertThat(listed).as(name).doesNotContain("Theirs");
        });
    }

    @Test
    void aFilterThatAdmitsSomethingIsNotTrivial() {
        // Guards the test above from passing on filters that all happen to admit nothing.
        AccessFilter mix = new AccessFilter(mine.getId(), false, Set.of(first.getId()), null,
                Map.of(second.getId(), own()));
        assertThat(all.stream().filter(indent -> mix.admits(IndentAccess.facts(indent))).map(Indent::getRemarks))
                .containsExactlyInAnyOrder("On first", "Raised by them on first", "Raised by them on second");
    }

    private Links own() {
        return new Links(Set.of(raiser.getId()), null, null, null);
    }

    private Indent indent(Organization org, String name, Project project, Employee raisedBy) {
        Indent indent = new Indent();
        indent.setOrganization(org);
        indent.setIndentNumber("IND-" + System.nanoTime());
        indent.setStatus(IndentStatus.PENDING);
        indent.setProject(project);
        indent.setCreatedBy(raisedBy);
        indent.setRemarks(name);
        em.persist(indent);
        return indent;
    }

    /** One line per indent, named after it. */
    private void line(Indent indent) {
        IndentItem line = new IndentItem();
        line.setIndent(indent);
        line.setOrganization(indent.getOrganization());
        line.setRequestedQuantity(1);
        line.setConvertedToPurchaseOrder(false);
        line.setRemarks(indent.getRemarks());
        em.persist(line);
    }
}
