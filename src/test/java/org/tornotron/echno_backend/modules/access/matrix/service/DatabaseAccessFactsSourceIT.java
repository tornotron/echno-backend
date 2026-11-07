package org.tornotron.echno_backend.modules.access.matrix.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessPartyLink;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.modules.access.matrix.repository.AccessFactsQueries;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.vendor.Vendor;

/**
 * Loading a person's facts from the matrix tables against a real CockroachDB: the three sources
 * of grants add together, a project assignment stays on its project, projects and party links come
 * along, another organization's rows stay out, and only the statuses that hold access do.
 *
 * <p>No org filter is enabled here on purpose: every query must name the organization itself.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AccessFactsQueries.class, DatabaseAccessFactsSource.class})
class DatabaseAccessFactsSourceIT extends AbstractIntegrationTest {

    @Autowired
    private DatabaseAccessFactsSource source;

    @Autowired
    private TestEntityManager em;

    private AccessFixtures fixtures;

    @BeforeEach
    void fixtures() {
        fixtures = new AccessFixtures(em.getEntityManager());
    }

    private static AccessSubject subject(Organization org, Employee person) {
        return new AccessSubject(org.getId(), person.getId(), person.getUser().getId());
    }

    @Test
    void grantsFromEverySourceAddTogether() {
        Organization org = fixtures.organization("Facts Org A");
        Employee person = fixtures.employee(org, "facts-a");
        AccessRole baseline = fixtures.newRole(org, "employee");
        baseline.setBaseline(true);
        baseline.grant(AccessPermission.of("leave:read"), AccessScope.OWN);
        em.persist(baseline);
        AccessRole manager = fixtures.newRole(org, "project-manager");
        manager.grant(AccessPermission.of("wbs:edit"), AccessScope.ASSIGNED_PROJECTS);
        em.persist(manager);
        fixtures.assign(org, person, manager, null);
        fixtures.grant(org, person, "wbs:edit", AccessScope.OWN, null);
        fixtures.grant(org, person, "tasks:read", AccessScope.RELATED, null);
        em.flush();
        em.clear();

        AccessFacts facts = source.loadHoldings(subject(org, person));

        assertThat(facts.active()).isTrue();
        assertThat(facts.grants()).containsOnlyKeys("leave:read", "wbs:edit", "tasks:read");
        assertThat(facts.grants().get("leave:read")).containsExactly(AccessScope.OWN);
        assertThat(facts.grants().get("wbs:edit"))
                .containsExactlyInAnyOrder(AccessScope.ASSIGNED_PROJECTS, AccessScope.OWN);
        assertThat(facts.grants().get("tasks:read")).containsExactly(AccessScope.RELATED);
        assertThat(facts.projectGrants()).isEmpty();
    }

    @Test
    void aProjectAssignmentStaysOnItsProject() {
        Organization org = fixtures.organization("Facts Org B");
        Employee person = fixtures.employee(org, "facts-b");
        Project tower = fixtures.project(org, "Tower B1");
        Project annex = fixtures.project(org, "Tower B2");
        AccessRole engineer = fixtures.newRole(org, "site-engineer");
        engineer.grant(AccessPermission.of("wbs:edit"), AccessScope.ALL);
        em.persist(engineer);
        fixtures.assign(org, person, engineer, tower.getId());
        fixtures.grant(org, person, "tasks:read", AccessScope.OWN, annex.getId());
        em.flush();
        em.clear();

        AccessFacts facts = source.loadHoldings(subject(org, person));

        assertThat(facts.grants()).isEmpty();
        assertThat(facts.projectGrants()).containsOnlyKeys(tower.getId(), annex.getId());
        assertThat(facts.projectGrants().get(tower.getId()).get("wbs:edit")).containsExactly(AccessScope.ALL);
        assertThat(facts.projectGrants().get(annex.getId()).get("tasks:read")).containsExactly(AccessScope.OWN);
    }

    @Test
    void projectsAndPartyLinksComeAlong() {
        Organization org = fixtures.organization("Facts Org C");
        Employee person = fixtures.employee(org, "facts-c");
        Project tower = fixtures.project(org, "Tower C");
        fixtures.project(org, "Tower C2");
        tower.getEmployees().add(person);
        Vendor vendor = fixtures.vendor(org, "Gamma Scaffolding");
        em.persist(AccessPartyLink.toVendor(org, person.getId(), vendor.getId()));
        em.flush();
        em.clear();

        AccessFacts facts = source.loadHoldings(subject(org, person));

        assertThat(facts.projectIds()).containsExactly(tower.getId());
        assertThat(facts.vendorIds()).containsExactly(vendor.getId());
        assertThat(facts.customerIds()).isEmpty();
    }

    @Test
    void anotherOrganizationsBaselineDoesNotReachThisOne() {
        Organization mine = fixtures.organization("Facts Org D");
        Organization theirs = fixtures.organization("Facts Org E");
        Employee person = fixtures.employee(mine, "facts-d", EmployeeStatus.probation);
        AccessRole theirBaseline = fixtures.newRole(theirs, "employee");
        theirBaseline.setBaseline(true);
        theirBaseline.grant(AccessPermission.of("finance:read"), AccessScope.ALL);
        em.persist(theirBaseline);
        em.flush();
        em.clear();

        AccessFacts facts = source.loadHoldings(subject(mine, person));

        assertThat(facts.grants()).isEmpty();
    }

    @Test
    void anAssignmentInOneOrganizationGivesNothingInAnother() {
        Organization mine = fixtures.organization("Facts Org F");
        Organization theirs = fixtures.organization("Facts Org G");
        Employee person = fixtures.employee(mine, "facts-f");
        AccessRole engineer = fixtures.newRole(mine, "site-engineer");
        engineer.grant(AccessPermission.of("wbs:edit"), AccessScope.ALL);
        em.persist(engineer);
        fixtures.assign(mine, person, engineer, null);
        fixtures.grant(mine, person, "tasks:read", AccessScope.ALL, null);
        em.flush();
        em.clear();

        assertThat(source.isActiveMember(new AccessSubject(theirs.getId(), person.getId(), null))).isFalse();
        assertThat(source.loadHoldings(new AccessSubject(theirs.getId(), person.getId(), null)).grants()).isEmpty();
    }

    @Test
    void onlyTheStatusesThatHoldAccessDo() {
        Organization org = fixtures.organization("Facts Org H");
        for (EmployeeStatus status : EmployeeStatus.values()) {
            Employee person = fixtures.employee(org, "facts-h-" + status, status);
            boolean holds = status == EmployeeStatus.active || status == EmployeeStatus.onLeave
                    || status == EmployeeStatus.probation;
            assertThat(source.isActiveMember(subject(org, person))).as("%s", status).isEqualTo(holds);
        }
    }

    @Test
    void aStatusNeverSetHoldsNothing() {
        Organization org = fixtures.organization("Facts Org I");
        Employee person = fixtures.employee(org, "facts-i", null);
        em.flush();

        assertThat(source.isActiveMember(subject(org, person))).isFalse();
    }
}
