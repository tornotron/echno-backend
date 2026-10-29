package org.tornotron.echno_backend.modules.access.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
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
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessEmployeeGrant;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessEmployeeRole;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessGroup;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessGroupKind;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.modules.access.matrix.seed.StandardRoleSeeder;
import org.tornotron.echno_backend.modules.access.matrix.seed.StandardRoles;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGroupDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessRoleDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessUserDto;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;

/**
 * What the Roles &amp; Permissions screen reads, against a real CockroachDB: an organization's
 * roles and groups with their grants, members and roles, and nothing from another organization.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AccessAdminReader.class, StandardRoleSeeder.class})
class AccessAdminReaderIT extends AbstractIntegrationTest {

    @Autowired
    private AccessAdminReader reader;

    @Autowired
    private StandardRoleSeeder seeder;

    @Autowired
    private TestEntityManager em;

    @Test
    void rolesComeWithGrantsMembersAndGrantableRolesForOneOrganizationOnly() {
        Organization org = persistOrganization("Reader Org A");
        Organization other = persistOrganization("Reader Org B");
        Employee keeper = persistEmployee(org, "reader-keeper");
        seeder.seed(org.getId());
        seeder.seed(other.getId());
        em.flush();
        AccessRole storeKeeper = em.getEntityManager()
                .createQuery("SELECT r FROM AccessRole r WHERE r.organization.id = :o AND r.slug = 'store-keeper'",
                        AccessRole.class)
                .setParameter("o", org.getId()).getSingleResult();
        AccessEmployeeRole assignment = new AccessEmployeeRole();
        assignment.setOrganization(org);
        assignment.setEmployeeId(keeper.getId());
        assignment.setRole(storeKeeper);
        em.persist(assignment);
        em.flush();
        em.clear();

        List<AccessRoleDto> roles = reader.roles(org.getId());

        assertThat(roles).hasSize(StandardRoles.all().size());
        assertThat(roles.get(0).slug()).isEqualTo(StandardRoles.MEMBER);
        assertThat(roles).filteredOn(r -> r.slug().equals("store-keeper")).singleElement().satisfies(role -> {
            assertThat(role.memberIds()).containsExactly(keeper.getId());
            assertThat(role.grants()).anySatisfy(g -> {
                assertThat(g.permission()).isEqualTo("goods-receipts:create");
                assertThat(g.scope()).isEqualTo("ALL");
            });
        });
        AccessRoleDto hr = roles.stream().filter(r -> r.slug().equals("hr-admin")).findFirst().orElseThrow();
        List<UUID> roleIds = roles.stream().map(AccessRoleDto::id).toList();
        assertThat(hr.grantableRoleIds()).isNotEmpty().allSatisfy(id -> assertThat(roleIds).contains(id));
    }

    @Test
    void groupsComeWithMembersRolesAndGrantsForOneOrganizationOnly() {
        Organization org = persistOrganization("Reader Org C");
        Organization other = persistOrganization("Reader Org D");
        Employee accountant = persistEmployee(org, "reader-accountant");
        AccessRole auditor = new AccessRole();
        auditor.setOrganization(org);
        auditor.setSlug("auditor");
        auditor.setName("Auditor");
        em.persist(auditor);
        AccessGroup finance = new AccessGroup();
        finance.setOrganization(org);
        finance.setKind(AccessGroupKind.DEPARTMENT);
        finance.setName("Finance");
        finance.getMemberIds().add(accountant.getId());
        finance.getRoles().add(auditor);
        finance.grant(AccessPermission.of("finance:view"), AccessScope.ALL);
        em.persist(finance);
        AccessGroup elsewhere = new AccessGroup();
        elsewhere.setOrganization(other);
        elsewhere.setName("Finance");
        em.persist(elsewhere);
        em.flush();
        em.clear();

        List<AccessGroupDto> groups = reader.groups(org.getId());

        assertThat(groups).singleElement().satisfies(group -> {
            assertThat(group.kind()).isEqualTo("DEPARTMENT");
            assertThat(group.memberIds()).containsExactly(accountant.getId());
            assertThat(group.roleIds()).containsExactly(auditor.getId());
            assertThat(group.grants()).singleElement().satisfies(g -> {
                assertThat(g.permission()).isEqualTo("finance:view");
                assertThat(g.locked()).isFalse();
            });
        });
    }

    @Test
    void peopleComeWithTheirRolesGroupsAndGrantsForOneOrganizationOnly() {
        Organization org = persistOrganization("Reader Org E");
        Organization other = persistOrganization("Reader Org F");
        Employee engineer = persistEmployee(org, "reader-engineer");
        Employee leaver = persistEmployee(org, "reader-leaver");
        leaver.setStatus(EmployeeStatus.resigned);
        persistEmployee(other, "reader-outsider");
        AccessRole site = new AccessRole();
        site.setOrganization(org);
        site.setSlug("site-engineer");
        site.setName("Site Engineer");
        em.persist(site);
        AccessEmployeeRole assignment = new AccessEmployeeRole();
        assignment.setOrganization(org);
        assignment.setEmployeeId(engineer.getId());
        assignment.setRole(site);
        em.persist(assignment);
        AccessGroup crew = new AccessGroup();
        crew.setOrganization(org);
        crew.setName("Tower A crew");
        crew.getMemberIds().add(engineer.getId());
        em.persist(crew);
        AccessEmployeeGrant direct = new AccessEmployeeGrant();
        direct.setOrganization(org);
        direct.setEmployeeId(engineer.getId());
        direct.setPermissionKey("tasks:view");
        direct.setScope(AccessScope.RELATED);
        em.persist(direct);
        em.flush();
        em.clear();

        List<AccessUserDto> people = reader.users(org.getId());

        assertThat(people).extracting(AccessUserDto::name).containsExactly("reader-engineer", "reader-leaver");
        assertThat(people.get(0)).satisfies(person -> {
            assertThat(person.active()).isTrue();
            assertThat(person.roleIds()).containsExactly(site.getId());
            assertThat(person.groupIds()).containsExactly(crew.getId());
            assertThat(person.grants()).singleElement()
                    .satisfies(g -> assertThat(g.scope()).isEqualTo("RELATED"));
        });
        assertThat(people.get(1).active()).isFalse();
    }

    private Organization persistOrganization(String name) {
        Organization organization = new Organization();
        organization.setOrganizationName(name);
        organization.setOrganizationAddress(name + " address");
        organization.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        organization.setOrganizationPhone("0000000000");
        em.persist(organization);
        return organization;
    }

    private Employee persistEmployee(Organization organization, String name) {
        User user = new User();
        user.setKeycloakId("kc-" + name);
        user.setName(name);
        em.persist(user);
        Employee employee = new Employee();
        employee.setOrganization(organization);
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
