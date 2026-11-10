package org.tornotron.echno_backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.identity.IdentityProvider;
import org.tornotron.echno_backend.common.identity.link.IdentityLinks;
import org.tornotron.echno_backend.common.identity.link.UserIdentityLookup;
import org.tornotron.echno_backend.common.mapper.AttachmentMapperImpl;
import org.tornotron.echno_backend.common.multitenancy.HibernateFilterConfig;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.UnscopedAccessGuard;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.organization.mapper.OrganizationMapper;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.dto.UserPatchDto;
import org.tornotron.echno_backend.user.mapper.UserMapperImpl;

/**
 * What an organization's system admin may change about a user account, against a real CockroachDB.
 *
 * <p>{@link User} is not tenant scoped, so the {@code orgFilter} never narrows a load of one by id.
 * The PATCH and DELETE behind the system-admin guard loaded the account by id alone, which let the
 * system admin of any organization, and anyone may found one, rename, re-address or delete any
 * account in the deployment. They now reach only accounts employed in the caller's organization, and
 * an account also employed elsewhere keeps its sign-in fields and its row: only its owner changes
 * the first, and leaving this organization is the employee flow, not a delete of the account.
 *
 * <p>The org filter aspect is loaded, so the organizations read is checked under the same filter the
 * running application applies.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({UserService.class, UserContextService.class, UserMapperImpl.class, AttachmentMapperImpl.class,
        AopAutoConfiguration.class, HibernateFilterConfig.class, UnscopedAccessGuard.class,
        SimpleMeterRegistry.class})
class UserCrossOrganizationChangeIT extends AbstractIntegrationTest {

    @Autowired
    private UserService users;

    @PersistenceContext
    private EntityManager em;

    @MockitoBean
    private IdentityProvider identityProvider;

    @MockitoBean
    private IdentityLinks identityLinks;

    // Resolves the signed-in subject to its user, as the user_identity table does in the application.
    @MockitoBean
    private UserIdentityLookup userIdentityLookup;

    @MockitoBean
    private AttachmentService attachmentService;

    @MockitoBean
    private FileStorageService fileStorageService;

    @MockitoBean
    private EmployeeMapper employeeMapper;

    @MockitoBean
    private OrganizationMapper organizationMapper;

    private Organization own;
    private User admin;
    private User colleague;
    private User outsider;
    private User shared;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        own = organization("Northbank Builders");
        Organization other = organization("Southfield Works");
        admin = employ(user("kc-admin-north", "Asha Admin", null), own, "N-1");
        colleague = employ(user("kc-colleague-north", "Bala Colleague", "bala@northbank.test"), own, "N-2");
        outsider = employ(user("kc-outsider-south", "Chitra Outsider", "chitra@southfield.test"), other, "S-1");
        shared = employ(user("kc-shared", "Dev Shared", "dev@shared.test"), own, "N-3");
        employ(shared, other, "S-2");
        em.flush();
        em.clear();
        TenantContext.setCurrentOrgId(own.getId());
        when(organizationMapper.toDto(any(Organization.class))).thenAnswer(call -> {
            OrganizationDto dto = new OrganizationDto();
            dto.setId(call.<Organization>getArgument(0).getId());
            return dto;
        });
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void aSystemAdminChangesAColleagueInTheirOrganization() {
        signInAs(admin);

        users.partialUpdateAnUser(Map.of("name", "Bala Renamed", "email", "bala.new@northbank.test"),
                colleague.getId(), null, null);

        User reloaded = reload(colleague);
        assertThat(reloaded.getName()).isEqualTo("Bala Renamed");
        assertThat(reloaded.getEmail()).isEqualTo("bala.new@northbank.test");
    }

    @Test
    void aSystemAdminCannotReachAUserOfAnotherOrganization() {
        signInAs(admin);

        assertThatThrownBy(() -> users.partialUpdateAnUser(Map.of("name", "Taken Over"), outsider.getId(), null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> users.deleteAnUser(outsider.getId()))
                .isInstanceOf(ResourceNotFoundException.class);

        User reloaded = reload(outsider);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getName()).isEqualTo("Chitra Outsider");
    }

    @Test
    void aSystemAdminCannotChangeWhatASharedUserSignsInWith() {
        signInAs(admin);

        assertThatThrownBy(() -> users.partialUpdateAnUser(Map.of("email", "attacker@example.test"),
                shared.getId(), null, null))
                .isInstanceOf(SharedUserException.class)
                .hasMessageContaining("email");
        assertThat(reload(shared).getEmail()).isEqualTo("dev@shared.test");

        // The rest of the profile is still this organization's administrator's to keep up.
        users.partialUpdateAnUser(Map.of("address", "12 Quay Road"), shared.getId(), null, null);
        assertThat(reload(shared).getAddress()).isEqualTo("12 Quay Road");
    }

    @Test
    void aBatchFromASystemAdminCannotChangeWhatASharedUserSignsInWith() {
        signInAs(admin);
        UserPatchDto patch = new UserPatchDto();
        patch.setId(shared.getId());
        patch.setUpdates(Map.of("email", "attacker@example.test"));

        assertThatThrownBy(() -> users.batchUpdateUser(List.of(patch)))
                .isInstanceOf(SharedUserException.class);
        assertThat(reload(shared).getEmail()).isEqualTo("dev@shared.test");
    }

    @Test
    void aSystemAdminCannotDeleteASharedUser() {
        signInAs(admin);

        assertThatThrownBy(() -> users.deleteAnUser(shared.getId()))
                .isInstanceOf(SharedUserException.class);
        assertThat(reload(shared)).isNotNull();
    }

    @Test
    void aSystemAdminDeletesAUserOnlyTheirOrganizationHolds() {
        signInAs(admin);

        users.deleteAnUser(colleague.getId());
        em.flush();
        em.clear();

        assertThat(em.find(User.class, colleague.getId())).isNull();
    }

    @Test
    void aSharedUserChangesTheirOwnSignInFields() {
        signInAs(shared);

        assertThatCode(() -> users.partialUpdateAnUser(Map.of("email", "dev.new@shared.test"),
                shared.getId(), null, null)).doesNotThrowAnyException();
        assertThat(reload(shared).getEmail()).isEqualTo("dev.new@shared.test");
    }

    @Test
    void theOrganizationsReadNamesOnlyTheCallersOrganization() {
        // The orgFilter narrows the employee query behind it to the current organization, so a system
        // admin asking about a shared user learns of no other organization.
        signInAs(admin);

        assertThat(users.getOrganizationsForCurrentUser(shared.getId()))
                .extracting(OrganizationDto::getId)
                .containsExactly(own.getId());
    }

    private User reload(User user) {
        em.flush();
        em.clear();
        return em.find(User.class, user.getId());
    }

    private void signInAs(User user) {
        when(userIdentityLookup.userId(user.getKeycloakId())).thenReturn(Optional.of(user.getId()));
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(user.getKeycloakId())
                .claim("sub", user.getKeycloakId())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ORG_MEMBER_" + own.getId())), user.getKeycloakId()));
    }

    private Organization organization(String name) {
        Organization organization = new Organization();
        organization.setOrganizationName(name);
        organization.setOrganizationAddress(name + " address");
        organization.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        organization.setOrganizationPhone("0000000000");
        em.persist(organization);
        return organization;
    }

    private User user(String keycloakId, String name, String email) {
        User user = new User();
        user.setKeycloakId(keycloakId);
        user.setName(name);
        user.setEmail(email);
        em.persist(user);
        return user;
    }

    private User employ(User user, Organization organization, String employeeId) {
        Employee employee = new Employee();
        employee.setOrganization(organization);
        employee.setUser(user);
        employee.setEmployeeName(user.getName());
        employee.setEmployeeId(employeeId);
        employee.setGender("U");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress(employeeId.toLowerCase() + "@emp.test");
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        employee.setJoiningDate(LocalDateTime.of(2024, 6, 1, 0, 0));
        employee.setDesignation("Site Engineer");
        employee.setDepartment("Execution");
        employee.setStatus(EmployeeStatus.active);
        employee.setOrgRoles(new HashSet<>());
        em.persist(employee);
        return user;
    }
}
