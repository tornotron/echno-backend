package org.tornotron.echno_backend.modules.access.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.exception.UnprocessableRequestException;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessProvisioning;
import org.tornotron.echno_backend.modules.access.api.AccessRoleAssignments;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.matrix.repository.AccessFactsQueries;
import org.tornotron.echno_backend.modules.access.matrix.seed.MatrixAccessProvisioning;
import org.tornotron.echno_backend.modules.access.matrix.seed.StandardRoleSeeder;
import org.tornotron.echno_backend.modules.access.matrix.seed.StandardRoles;
import org.tornotron.echno_backend.modules.access.matrix.service.CachedAccessFactsSource;
import org.tornotron.echno_backend.modules.access.matrix.service.DatabaseAccessFactsSource;
import org.tornotron.echno_backend.modules.access.matrix.service.MatrixAccessService;
import org.tornotron.echno_backend.modules.access.matrix.service.MatrixRoleAssignments;
import org.tornotron.echno_backend.modules.access.web.RequestedGrants.Grant;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGrantRequest;
import org.tornotron.echno_backend.modules.access.web.dto.AccessRoleDto;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;

/**
 * What the Roles &amp; Permissions screen changes, against a real CockroachDB: the changes land,
 * and a caller can neither change a role their roles may not assign nor give what they do not
 * hold.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AccessAdminWriter.class, AccessAdminReader.class, StandardRoleSeeder.class,
        MatrixAccessProvisioning.class, AccessFactsQueries.class, DatabaseAccessFactsSource.class,
        CachedAccessFactsSource.class, MatrixAccessService.class, MatrixRoleAssignments.class,
        AccessAdminWriterIT.Caching.class})
class AccessAdminWriterIT extends AbstractIntegrationTest {

    @TestConfiguration
    static class Caching {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager();
        }
    }

    @Autowired
    private AccessAdminWriter writer;

    @Autowired
    private AccessAdminReader reader;

    @Autowired
    private AccessProvisioning provisioning;

    @Autowired
    private AccessRoleAssignments assignments;

    @Autowired
    private AccessService access;

    @Autowired
    private TestEntityManager em;

    private Organization org;
    private AccessSubject admin;
    private AccessSubject hr;
    private Employee engineer;

    @BeforeEach
    void provision() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        org = persistOrganization("Writer Org " + tag);
        Employee owner = persistEmployee(org, "writer-owner-" + tag);
        Employee hrAdmin = persistEmployee(org, "writer-hr-" + tag);
        engineer = persistEmployee(org, "writer-engineer-" + tag);
        em.flush();
        provisioning.provisionOrganization(org.getId(), owner.getId());
        assignments.assign(org.getId(), hrAdmin.getId(), "hr-admin");
        em.flush();
        em.clear();
        admin = new AccessSubject(org.getId(), owner.getId(), null);
        hr = new AccessSubject(org.getId(), hrAdmin.getId(), null);
    }

    @Test
    void aNewRoleCanBeHandedOutByItsMaker() {
        UUID auditor = writer.createRole(admin, " Site Auditor ", "Read only", null);
        flushAndClear();

        AccessRoleDto created = role(auditor);
        assertThat(created.slug()).isEqualTo("site-auditor");
        assertThat(created.name()).isEqualTo("Site Auditor");
        assertThat(created.system()).isFalse();
        assertThat(created.grants()).isEmpty();
        assertThat(role(StandardRoles.SYSTEM_ADMIN).grantableRoleIds()).contains(auditor);
        assertThatThrownBy(() -> writer.createRole(admin, "site auditor", null, null))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void replacingGrantsAddsViewAndTakesEffectAtOnce() {
        UUID auditor = writer.createRole(admin, "Auditor", null, null);
        assignments.assign(org.getId(), engineer.getId(), "auditor");
        AccessSubject asEngineer = new AccessSubject(org.getId(), engineer.getId(), null);
        assertThat(access.check(asEngineer, AccessPermission.of("finance:view"))).isFalse();

        writer.replaceRoleGrants(admin, auditor, RequestedGrants.parse(List.of(
                new AccessGrantRequest("finance:approve", "ALL"))));
        flushAndClear();

        assertThat(role(auditor).grants()).extracting(g -> g.permission())
                .containsExactlyInAnyOrder("finance:approve", "finance:view");
        assertThat(access.check(asEngineer, AccessPermission.of("finance:view"))).isTrue();

        writer.replaceRoleGrants(admin, auditor, Set.of());
        flushAndClear();
        assertThat(role(auditor).grants()).isEmpty();
        assertThat(access.check(asEngineer, AccessPermission.of("finance:view"))).isFalse();
    }

    @Test
    void theBaselinesLockedGrantsStay() {
        UUID member = role(StandardRoles.MEMBER).id();

        writer.replaceRoleGrants(admin, member, Set.of());
        flushAndClear();

        assertThat(role(member).grants()).isNotEmpty().allSatisfy(g -> assertThat(g.locked()).isTrue());
    }

    @Test
    void aCallerGivesOnlyWhatTheyHold() {
        UUID siteEngineer = role("site-engineer").id();

        assertThatThrownBy(() -> writer.replaceRoleGrants(hr, siteEngineer,
                Set.of(new Grant(AccessPermission.of("finance:view"), AccessScope.ALL))))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("finance:view");

        // Nor take: replacing site-engineer's grants would take ones HR does not hold.
        assertThatThrownBy(() -> writer.replaceRoleGrants(hr, siteEngineer,
                Set.of(new Grant(AccessPermission.of("documents:view"), AccessScope.ALL))))
                .isInstanceOf(AccessDeniedException.class);

        UUID clerk = writer.createRole(hr, "Records Clerk", null, null);
        flushAndClear();
        writer.replaceRoleGrants(hr, clerk, Set.of(new Grant(AccessPermission.of("documents:view"), AccessScope.ALL)));
        flushAndClear();
        assertThat(role(clerk).grants()).extracting(g -> g.permission()).containsExactly("documents:view");
    }

    @Test
    void aCallerChangesOnlyRolesTheirRolesMayAssign() {
        UUID systemAdmin = role(StandardRoles.SYSTEM_ADMIN).id();
        UUID hrAdmin = role("hr-admin").id();
        UUID siteEngineer = role("site-engineer").id();

        assertThatThrownBy(() -> writer.updateRole(hr, systemAdmin, "Boss", null, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> writer.updateRole(hr, hrAdmin, "HR", null, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> writer.replaceGrantableRoles(hr, siteEngineer, List.of(systemAdmin)))
                .isInstanceOf(AccessDeniedException.class);

        writer.updateRole(hr, siteEngineer, "Site Engineer", "Field", "On site");
        UUID storeKeeper = role("store-keeper").id();
        writer.replaceGrantableRoles(hr, siteEngineer, List.of(storeKeeper));
        flushAndClear();
        assertThat(role(siteEngineer).subtitle()).isEqualTo("Field");
        assertThat(role(siteEngineer).grantableRoleIds()).containsExactly(storeKeeper);
    }

    @Test
    void onlyRolesAnOrganizationMadeCanBeDeleted() {
        UUID siteEngineer = role("site-engineer").id();
        assertThatThrownBy(() -> writer.deleteRole(admin, siteEngineer))
                .isInstanceOf(UnprocessableRequestException.class);

        UUID auditor = writer.createRole(admin, "Auditor", null, null);
        assignments.assign(org.getId(), engineer.getId(), "auditor");
        flushAndClear();
        writer.deleteRole(admin, auditor);
        flushAndClear();

        assertThat(reader.roles(org.getId())).extracting(AccessRoleDto::id).doesNotContain(auditor);
        assertThat(assignments.rolesOf(org.getId(), engineer.getId())).doesNotContain("auditor");
        assertThat(role(StandardRoles.SYSTEM_ADMIN).grantableRoleIds()).doesNotContain(auditor);
    }

    @Test
    void anotherOrganizationsRoleReadsAsAbsent() {
        Organization other = persistOrganization("Writer Other " + UUID.randomUUID().toString().substring(0, 8));
        Employee owner = persistEmployee(other, "writer-other-owner-" + UUID.randomUUID().toString().substring(0, 8));
        em.flush();
        provisioning.provisionOrganization(other.getId(), owner.getId());
        flushAndClear();
        UUID theirs = reader.roles(other.getId()).get(1).id();

        assertThatThrownBy(() -> writer.updateRole(admin, theirs, "Mine now", null, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private AccessRoleDto role(String slug) {
        return reader.roles(org.getId()).stream().filter(r -> r.slug().equals(slug)).findFirst().orElseThrow();
    }

    private AccessRoleDto role(UUID id) {
        return reader.roles(org.getId()).stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
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
