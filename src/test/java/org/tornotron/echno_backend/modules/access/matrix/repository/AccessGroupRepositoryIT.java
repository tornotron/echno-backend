package org.tornotron.echno_backend.modules.access.matrix.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessGroup;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessGroupKind;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;

/**
 * The group tables against a real CockroachDB: members, roles and grants round-trip, a member's
 * groups are found, and the org filter hides another organization's groups.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccessGroupRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private AccessGroupRepository groups;

    @Autowired
    private TestEntityManager em;

    @AfterEach
    void disableFilter() {
        em.getEntityManager().unwrap(Session.class).disableFilter("orgFilter");
    }

    @Test
    void aGroupKeepsItsMembersRolesAndGrants() {
        Organization org = persistOrganization("Group Org A");
        Employee member = persistEmployee(org, "member-a");
        AccessRole auditor = new AccessRole();
        auditor.setOrganization(org);
        auditor.setSlug("auditor");
        auditor.setName("Auditor");
        em.persist(auditor);

        AccessGroup finance = group(org, "Finance");
        finance.setKind(AccessGroupKind.DEPARTMENT);
        finance.getMemberIds().add(member.getId());
        finance.getRoles().add(auditor);
        finance.grant(AccessPermission.of("finance:view"), AccessScope.ALL);
        em.persist(finance);
        em.flush();
        em.clear();

        scopeTo(org);

        assertThat(groups.findByMember(member.getId())).singleElement().satisfies(g -> {
            assertThat(g.getKind()).isEqualTo(AccessGroupKind.DEPARTMENT);
            assertThat(g.getMemberIds()).containsExactly(member.getId());
            assertThat(g.getRoles()).extracting(AccessRole::getSlug).containsExactly("auditor");
            assertThat(g.getGrants()).singleElement()
                    .satisfies(grant -> assertThat(grant.getScope()).isEqualTo(AccessScope.ALL));
        });
    }

    @Test
    void anotherOrganizationsGroupsAreHidden() {
        Organization mine = persistOrganization("Group Org B");
        Organization theirs = persistOrganization("Group Org C");
        em.persist(group(mine, "Site team"));
        em.persist(group(theirs, "Site team"));
        em.flush();
        em.clear();

        scopeTo(mine);

        assertThat(groups.findAllScoped()).extracting(AccessGroup::getName).containsExactly("Site team");
        assertThat(groups.findAllScoped()).singleElement()
                .satisfies(g -> assertThat(g.getOrganization().getId()).isEqualTo(mine.getId()));
    }

    private AccessGroup group(Organization org, String name) {
        AccessGroup group = new AccessGroup();
        group.setOrganization(org);
        group.setName(name);
        return group;
    }

    private void scopeTo(Organization org) {
        em.getEntityManager().unwrap(Session.class)
                .enableFilter("orgFilter").setParameter("organizationId", org.getId());
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

    private Employee persistEmployee(Organization org, String name) {
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
        em.persist(employee);
        return employee;
    }
}
