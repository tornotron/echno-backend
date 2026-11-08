package org.tornotron.echno_backend.project;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.common.configuration.JpaAuditingConfig;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.finance.ledger.domain.Customer;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessFilter.Links;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * The project queries admit exactly the projects {@link AccessFilter#admits} does, on CockroachDB, so
 * a list and a check on one project can never disagree. Covers the plain listing, the searched one
 * and the listing of one employee's projects.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// Customer carries Spring Data's AuditingEntityListener, so this slice imports the auditing
// configuration for a handler of its own; without it the listener is configured by whichever earlier
// context last enabled auditing, and a persist fails once the context cache has closed that one.
@Import(JpaAuditingConfig.class)
class ProjectAccessFilterIT extends AbstractIntegrationTest {

    @Autowired
    private EntityManager em;

    @Autowired
    private ProjectRepository projects;

    private Organization mine;
    private Project first;
    private Project second;
    private Employee member;
    private Employee colleague;
    private Customer customer;
    private List<Project> all;

    @BeforeEach
    void someProjects() {
        AccessFixtures fixtures = new AccessFixtures(em);
        mine = fixtures.organization("Filter " + System.nanoTime());
        Organization theirs = fixtures.organization("Filter other " + System.nanoTime());
        member = fixtures.employee(mine, "member-" + System.nanoTime());
        colleague = fixtures.employee(mine, "colleague-" + System.nanoTime());
        customer = fixtures.customer(mine, "C" + System.nanoTime());
        first = project(mine, "First", null, colleague);
        second = project(mine, "Second", null);
        all = List.of(
                first,
                second,
                project(mine, "With the member", null, member),
                // Two matching members must still list the project once.
                project(mine, "With the member and a colleague", null, member, colleague),
                project(mine, "For the customer", customer.getId()),
                project(mine, "For the customer, with the member", customer.getId(), member),
                // Another organization's project, with the same member, so only the organization tells them apart.
                project(theirs, "Theirs", null, member));
        em.flush();
        em.clear();
    }

    @Test
    void everyKindOfFilterAdmitsTheSameProjectsInTheQueryAsInMemory() {
        Long org = mine.getId();
        Long withMember = idOf("With the member");
        Map<String, AccessFilter> filters = Map.of(
                "all", AccessFilter.all(org),
                "none", AccessFilter.none(org),
                "one project", new AccessFilter(org, false, Set.of(first.getId()), null, null),
                "their team", new AccessFilter(org, false, null, team(), null),
                "their customer", new AccessFilter(org, false, null, client(), null),
                "team on one project", new AccessFilter(org, false, null, null, Map.of(withMember, team())),
                "customer on a project that has none", new AccessFilter(org, false, null, null,
                        Map.of(second.getId(), client())),
                "an owner, which no project has", new AccessFilter(org, false, null,
                        new Links(Set.of(member.getId()), null, null, null), null),
                "a mix", new AccessFilter(org, false, Set.of(second.getId()), client(),
                        Map.of(withMember, team())));

        filters.forEach((name, filter) -> {
            Set<String> expected = names(all.stream().filter(project -> filter.admits(factsOf(project))).toList());

            assertThat(names(projects.findAll(ProjectAccess.admittedBy(filter), PageRequest.of(0, 50)).getContent()))
                    .as(name).isEqualTo(expected);
            assertThat(projects.count(ProjectAccess.admittedBy(filter)))
                    .as("%s, counted", name).isEqualTo(expected.size());
            assertThat(names(projects.findAll(ProjectService.named("%with%").and(ProjectAccess.admittedBy(filter)))))
                    .as("%s, searched", name)
                    .isEqualTo(expected.stream().filter(n -> n.toLowerCase().contains("with")).collect(Collectors.toSet()));
            assertThat(names(projects.findAll(ProjectService.withMember(member.getId())
                    .and(ProjectAccess.admittedBy(filter)))))
                    .as("%s, the member's projects", name)
                    .isEqualTo(expected.stream().filter(n -> n.contains("member")).collect(Collectors.toSet()));
        });
    }

    @Test
    void aFilterThatAdmitsSomethingIsNotTrivial() {
        // Guards the test above from passing on filters that all happen to admit nothing.
        AccessFilter mix = new AccessFilter(mine.getId(), false, Set.of(second.getId()), client(),
                Map.of(idOf("With the member"), team()));
        assertThat(names(all.stream().filter(project -> mix.admits(factsOf(project))).toList()))
                .containsExactlyInAnyOrder("Second", "With the member", "For the customer",
                        "For the customer, with the member");
    }

    private Links team() {
        return new Links(null, Set.of(member.getId()), null, null);
    }

    private Links client() {
        return new Links(null, null, Set.of(customer.getId()), null);
    }

    private Long idOf(String name) {
        return all.stream().filter(project -> project.getProjectName().equals(name)).findFirst().orElseThrow().getId();
    }

    /** The facts the service states for a project, from what was persisted. */
    private RecordFacts factsOf(Project project) {
        return ProjectAccess.facts(project.getOrganization().getId(), project);
    }

    private static Set<String> names(List<Project> projects) {
        return projects.stream().map(Project::getProjectName).collect(Collectors.toSet());
    }

    private Project project(Organization org, String name, UUID customerId, Employee... team) {
        Project project = new Project();
        project.setOrganization(org);
        project.setProjectName(name);
        project.setCustomerId(customerId);
        project.setEmployees(new java.util.ArrayList<>(List.of(team)));
        em.persist(project);
        return project;
    }
}
