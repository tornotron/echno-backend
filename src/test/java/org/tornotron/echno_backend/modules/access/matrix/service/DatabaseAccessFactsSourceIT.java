package org.tornotron.echno_backend.modules.access.matrix.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessEmployeeGrant;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessEmployeeRole;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessGroup;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessPartyLink;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.modules.access.matrix.repository.AccessFactsQueries;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.vendor.Vendor;
import org.tornotron.echno_backend.vendor.enums.VendorStatus;
import org.tornotron.echno_backend.vendor.enums.VendorType;

/**
 * Loading a person's facts from the matrix tables against a real CockroachDB: the five sources
 * of grants add together, projects and party links come along, another organization's rows stay
 * out, and a person who is not an active member holds nothing.
 *
 * <p>No org filter is enabled here on purpose: facts are loaded before the tenant filter runs,
 * so every query must name the organization itself.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AccessFactsQueries.class, DatabaseAccessFactsSource.class})
class DatabaseAccessFactsSourceIT extends AbstractIntegrationTest {

    @Autowired
    private DatabaseAccessFactsSource source;

    @Autowired
    private TestEntityManager em;

    @Test
    void grantsFromEverySourceAddTogether() {
        Organization org = persistOrganization("Facts Org A");
        Employee person = persistEmployee(org, "facts-a", null);

        AccessRole member = role(org, "member", true);
        member.grant(AccessPermission.of("leave:view"), AccessScope.OWN, true);
        AccessRole manager = role(org, "project-manager", false);
        manager.grant(AccessPermission.of("wbs:edit"), AccessScope.ASSIGNED_PROJECTS, false);
        AccessRole auditor = role(org, "auditor", false);
        auditor.grant(AccessPermission.of("finance:view"), AccessScope.ALL, false);
        em.persist(member);
        em.persist(manager);
        em.persist(auditor);

        AccessEmployeeRole assigned = new AccessEmployeeRole();
        assigned.setOrganization(org);
        assigned.setEmployeeId(person.getId());
        assigned.setRole(manager);
        em.persist(assigned);

        AccessGroup finance = new AccessGroup();
        finance.setOrganization(org);
        finance.setName("Finance");
        finance.getMemberIds().add(person.getId());
        finance.getRoles().add(auditor);
        finance.grant(AccessPermission.of("wbs:edit"), AccessScope.ALL);
        em.persist(finance);

        AccessEmployeeGrant direct = new AccessEmployeeGrant();
        direct.setOrganization(org);
        direct.setEmployeeId(person.getId());
        direct.setPermissionKey("tasks:view");
        direct.setScope(AccessScope.RELATED);
        em.persist(direct);
        em.flush();
        em.clear();

        AccessFacts facts = source.load(subject(org, person));

        assertThat(facts.active()).isTrue();
        assertThat(facts.grants()).containsOnlyKeys("leave:view", "wbs:edit", "finance:view", "tasks:view");
        assertThat(facts.grants().get("leave:view")).containsExactly(AccessScope.OWN);
        assertThat(facts.grants().get("wbs:edit"))
                .containsExactlyInAnyOrder(AccessScope.ASSIGNED_PROJECTS, AccessScope.ALL);
        assertThat(facts.grants().get("finance:view")).containsExactly(AccessScope.ALL);
        assertThat(facts.grants().get("tasks:view")).containsExactly(AccessScope.RELATED);
    }

    @Test
    void projectsAndPartyLinksComeAlong() {
        Organization org = persistOrganization("Facts Org B");
        Employee person = persistEmployee(org, "facts-b", EmployeeStatus.active);
        Project tower = persistProject(org, "Tower A");
        persistProject(org, "Tower B");
        tower.getEmployees().add(person);
        Vendor vendor = persistVendor(org, "Gamma Scaffolding");
        em.persist(AccessPartyLink.toVendor(org, person.getId(), vendor.getId()));
        em.flush();
        em.clear();

        AccessFacts facts = source.load(subject(org, person));

        assertThat(facts.projectIds()).containsExactly(tower.getId());
        assertThat(facts.vendorIds()).containsExactly(vendor.getId());
        assertThat(facts.customerIds()).isEmpty();
    }

    @Test
    void anotherOrganizationsBaselineDoesNotReachThisOne() {
        Organization mine = persistOrganization("Facts Org C");
        Organization theirs = persistOrganization("Facts Org D");
        Employee person = persistEmployee(mine, "facts-c", EmployeeStatus.probation);
        AccessRole theirBaseline = role(theirs, "member", true);
        theirBaseline.grant(AccessPermission.of("finance:view"), AccessScope.ALL, true);
        em.persist(theirBaseline);
        em.flush();
        em.clear();

        AccessFacts facts = source.load(subject(mine, person));

        assertThat(facts.active()).isTrue();
        assertThat(facts.grants()).isEmpty();
    }

    @Test
    void someoneWhoIsNotAnActiveMemberHoldsNothing() {
        Organization org = persistOrganization("Facts Org E");
        Organization other = persistOrganization("Facts Org F");
        AccessRole member = role(org, "member", true);
        member.grant(AccessPermission.of("leave:view"), AccessScope.OWN, true);
        em.persist(member);
        Employee resigned = persistEmployee(org, "facts-e", EmployeeStatus.resigned);
        Employee elsewhere = persistEmployee(other, "facts-f", EmployeeStatus.active);
        em.flush();
        em.clear();

        assertThat(source.load(subject(org, resigned))).isEqualTo(AccessFacts.noAccess());
        assertThat(source.load(new AccessSubject(org.getId(), elsewhere.getId(), null)))
                .isEqualTo(AccessFacts.noAccess());
    }

    private AccessSubject subject(Organization org, Employee person) {
        return new AccessSubject(org.getId(), person.getId(), person.getUser().getId());
    }

    private AccessRole role(Organization org, String slug, boolean baseline) {
        AccessRole role = new AccessRole();
        role.setOrganization(org);
        role.setSlug(slug);
        role.setName(slug);
        role.setBaseline(baseline);
        return role;
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

    private Employee persistEmployee(Organization org, String name, EmployeeStatus status) {
        User user = new User();
        user.setKeycloakId("kc-" + name);
        user.setName(name);
        em.persist(user);
        Employee employee = new Employee();
        employee.setOrganization(org);
        employee.setUser(user);
        employee.setEmployeeName(name);
        employee.setGender("U");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress(name + "@emp.test");
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        employee.setStatus(status);
        em.persist(employee);
        return employee;
    }

    private Project persistProject(Organization org, String name) {
        Project project = new Project();
        project.setOrganization(org);
        project.setProjectName(name);
        project.setEmployees(new ArrayList<>());
        em.persist(project);
        return project;
    }

    private Vendor persistVendor(Organization org, String name) {
        Vendor vendor = new Vendor();
        vendor.setVendorName(name);
        vendor.setType(VendorType.MATERIALS);
        vendor.setStatus(VendorStatus.ACTIVE);
        vendor.setVendorEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        vendor.setOrganization(org);
        em.persist(vendor);
        return vendor;
    }
}
