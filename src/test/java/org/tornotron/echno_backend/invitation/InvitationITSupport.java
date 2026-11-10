package org.tornotron.echno_backend.invitation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.tornotron.echno_backend.modules.access.matrix.service.MatrixRoleHolders;
import org.tornotron.echno_backend.employee.TeamAccess;
import org.tornotron.echno_backend.attendance.mapper.ShiftTimingMapperImpl;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.identity.EchnoOrgId;
import org.tornotron.echno_backend.common.identity.fake.FakeIdentityProvider;
import org.tornotron.echno_backend.common.identity.fake.FakeIdentityTokenMapper;
import org.tornotron.echno_backend.common.identity.link.IdentityLinks;
import org.tornotron.echno_backend.common.identity.task.IdentityTaskQueue;
import org.tornotron.echno_backend.common.mapper.AttachmentMapperImpl;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeHierarchyService;
import org.tornotron.echno_backend.employee.EmployeeService;
import org.tornotron.echno_backend.employee.dto.EmployeeJoinOrgDto;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapperImpl;
import org.tornotron.echno_backend.invitation.dto.InvitationCreationDto;
import org.tornotron.echno_backend.invitation.dto.InvitationRoleDto;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.modules.access.matrix.repository.AccessFactsQueries;
import org.tornotron.echno_backend.modules.access.matrix.service.AccessChangeRecorder;
import org.tornotron.echno_backend.modules.access.matrix.service.AccessRoleReconciler;
import org.tornotron.echno_backend.modules.access.matrix.service.CachedAccessFactsSource;
import org.tornotron.echno_backend.modules.access.matrix.service.DatabaseAccessFactsSource;
import org.tornotron.echno_backend.modules.access.matrix.service.LegacyRoleMirror;
import org.tornotron.echno_backend.modules.access.matrix.service.MatrixRoleAssignments;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Invitations on CockroachDB, with the real assignment rules and the fake provider. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InvitationService.class, InvitationDeliveryService.class, IdentityTaskQueue.class,
        MatrixRoleAssignments.class, LegacyRoleMirror.class, AccessRoleReconciler.class, AccessChangeRecorder.class,
        CachedAccessFactsSource.class, DatabaseAccessFactsSource.class, AccessFactsQueries.class,
        ConcurrentMapCacheManager.class, CurrentEmployeeService.class, UserContextService.class,
        EmployeeService.class, EmployeeHierarchyService.class, MatrixRoleHolders.class, EmployeeMapperImpl.class,
        AttachmentMapperImpl.class, ShiftTimingMapperImpl.class, OrganizationSecurityService.class,
        JacksonAutoConfiguration.class, IdentityLinkService.class, IdentityLinks.class, InvitationSyncJob.class,
        InvitationITSupport.Provider.class})
abstract class InvitationITSupport extends AbstractIntegrationTest {

    /** Nobody here edits every employee's record. */
    @MockitoBean
    protected TeamAccess teamAccess;

    @TestConfiguration(proxyBeanMethods = false)
    static class Provider {
        @Bean
        FakeIdentityProvider fakeIdentityProvider() {
            return new FakeIdentityProvider();
        }

        @Bean
        FakeIdentityTokenMapper fakeIdentityTokenMapper() {
            return new FakeIdentityTokenMapper();
        }
    }

    @Autowired
    protected jakarta.persistence.EntityManager em;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected InvitationService invitations;
    @Autowired
    protected InvitationDeliveryService delivery;
    @Autowired
    protected EmployeeService employeeService;
    @Autowired
    protected FakeIdentityProvider provider;
    @Autowired
    protected AccessRoleReconciler reconciler;

    @MockitoBean
    protected FileStorageService fileStorageService;

    protected AccessFixtures fixtures;
    protected Organization organization;
    /** The reporting manager invitations name: the last person signed in. */
    protected Long manager;

    @BeforeEach
    void anOrganization() {
        fixtures = new AccessFixtures(em);
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;  // A test that commits sets up its own rows.
        }
        organization = anotherOrganization("Invitations");
        TenantContext.setCurrentOrgId(organization.getId());
    }

    @AfterEach
    void noCallerLeftBehind() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    protected Organization anotherOrganization(String name) {
        Organization created = fixtures.organization(name + " " + System.nanoTime());
        em.flush();
        provider.createOrganization(new EchnoOrgId(created.getId()), created.getOrganizationName());
        return created;
    }

    protected Employee employee(String name) {
        Employee person = fixtures.employee(organization, name + "-" + System.nanoTime(), EmployeeStatus.active);
        em.flush();
        return person;
    }

    /** An administrator of the current organization, signed in, holding the old role the converter mints. */
    protected Employee signedInAs(OrgRole legacyRole) {
        Employee person = employee(legacyRole.getGroupName());
        employeeService.assignOrgRoleAsSystem(person.getId(), legacyRole);
        em.flush();
        List<SimpleGrantedAuthority> authorities = Arrays.stream(new String[]{legacyRole.getGroupName()})
                .map(role -> new SimpleGrantedAuthority("ORG_" + organization.getId() + "_ROLE_" + role))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                person.getUser().getKeycloakId(), null, authorities));
        manager = person.getId();
        return person;
    }

    protected UUID role(String slug) {
        return role(organization, slug);
    }

    protected UUID role(Organization in, String slug) {
        return jdbc.queryForObject("SELECT id FROM access_role WHERE organization_id = ? AND slug = ?",
                UUID.class, in.getId(), slug);
    }

    protected InvitationCreationDto invitation(String email, InvitationRoleDto... roles) {
        EmployeeJoinOrgDto employee = new EmployeeJoinOrgDto();
        employee.setDesignation("Site Engineer");
        employee.setDepartment("Civil");
        employee.setManagerId(manager);
        return new InvitationCreationDto(email, List.of(roles), employee);
    }

    protected List<String> tasksFor(UUID invitation) {
        return jdbc.queryForList("SELECT kind FROM identity_task WHERE invitation_id = ? AND status = 'PENDING'",
                String.class, invitation);
    }

    protected List<String> logActionsAbout(UUID invitation) {
        em.flush();
        return jdbc.queryForList("""
                SELECT action FROM access_change_log
                WHERE organization_id = ? AND target_type = 'invitation' AND target_id = ?
                ORDER BY changed_at
                """, String.class, organization.getId(), invitation.toString());
    }

    protected String providerIdOf(UUID invitation) {
        return jdbc.queryForObject("SELECT provider_invitation_id FROM invitation WHERE id = ?", String.class, invitation);
    }
}
