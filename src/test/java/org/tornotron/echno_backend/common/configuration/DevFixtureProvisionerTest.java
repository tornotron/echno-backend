package org.tornotron.echno_backend.common.configuration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.KeycloakGroupService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.EmployeeService;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.employee.dto.EmployeeJoinOrgDto;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationOnboardingSeeder;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain Mockito test (no Spring context, no database) for the application half of the local-dev
 * fixture's identity.
 *
 * <p>The bug this covers is the one that made the fixture unusable: it had a Keycloak account and
 * nothing else, so the organization picker, which reads the employee table, showed it nothing, and
 * its only group membership was a parent org group, which yields ORG_MEMBER_x and passes no role
 * check. Both halves have to be written, in the fixture's own organization.
 */
@ExtendWith(MockitoExtension.class)
class DevFixtureProvisionerTest {

    private static final String KEYCLOAK_ID = "0e1e0b0c-dev-fixture-id";
    private static final String DISPLAY_NAME = "Local Dev";
    private static final String EMAIL = "local-dev@echno.local";

    @Mock
    private UserRepository userRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private EmployeeService employeeService;
    @Mock
    private KeycloakGroupService keycloakGroupService;
    @Mock
    private OrganizationOnboardingSeeder onboardingSeeder;

    @InjectMocks
    private DevFixtureProvisioner provisioner;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setKeycloakId(KEYCLOAK_ID);
        user.setName(DISPLAY_NAME);
        user.setEmail(EMAIL);
        return user;
    }

    private static Organization organization(Long id) {
        Organization organization = new Organization();
        organization.setId(id);
        organization.setOrganizationName(DevFixtureProvisioner.DEV_ORG_NAME);
        return organization;
    }

    private static EmployeeDto employeeDto(Long id) {
        EmployeeDto dto = new EmployeeDto();
        dto.setId(id);
        return dto;
    }

    @Test
    void provisionsBothHalvesOfTheIdentityInItsOwnOrganization() {
        when(userRepository.findUserByKeycloakId(KEYCLOAK_ID)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(7L);
            return saved;
        });
        when(organizationRepository.findOrganizationByOrganizationName(DevFixtureProvisioner.DEV_ORG_NAME))
                .thenReturn(Optional.empty());
        when(organizationRepository.save(any(Organization.class))).thenAnswer(invocation -> {
            Organization saved = invocation.getArgument(0);
            saved.setId(42L);
            return saved;
        });
        when(employeeRepository.findByUserIdAndOrganizationId(7L, 42L)).thenReturn(Optional.empty());
        when(employeeService.joinOrganization(eq(7L), eq(42L), any(EmployeeJoinOrgDto.class)))
                .thenReturn(employeeDto(19L));

        provisioner.provision(KEYCLOAK_ID, DISPLAY_NAME, EMAIL);

        // The user row is the link the application resolves a JWT subject by.
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getKeycloakId()).isEqualTo(KEYCLOAK_ID);
        assertThat(savedUser.getEmail()).isEqualTo(EMAIL);
        // joinOrganization copies these onto NOT NULL employee columns, so they cannot be left null.
        assertThat(savedUser.getName()).isNotBlank();
        assertThat(savedUser.getGender()).isNotBlank();
        assertThat(savedUser.getPhone()).isNotBlank();
        assertThat(savedUser.getDateOfBirth()).isNotNull();

        // The fixture's own organization, with the Keycloak group that has to exist before anyone
        // joins it, and the finance defaults a usable organization needs.
        ArgumentCaptor<Organization> orgCaptor = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository).save(orgCaptor.capture());
        assertThat(orgCaptor.getValue().getOrganizationName())
                .isEqualTo(DevFixtureProvisioner.DEV_ORG_NAME);
        verify(keycloakGroupService).createOrganizationGroup("42", DevFixtureProvisioner.DEV_ORG_NAME);
        verify(onboardingSeeder).seedFinanceDefaults(42L);

        // The employee row, written through the application's own onboarding path.
        verify(employeeService).joinOrganization(eq(7L), eq(42L), any(EmployeeJoinOrgDto.class));

        // And the role subgroup, which is the half the authority checks actually read.
        verify(employeeService).assignOrgRole(19L, OrgRole.SYSTEM_ADMIN);
    }

    /**
     * assignOrgRole resolves the employee against the tenant context, so the provisioner has to set
     * it, and has to put it back afterwards: this runs on the startup thread, which is not a request.
     */
    @Test
    void setsTheTenantForTheRoleAssignmentAndRestoresItAfterwards() {
        when(userRepository.findUserByKeycloakId(KEYCLOAK_ID)).thenReturn(Optional.of(user(7L)));
        when(organizationRepository.findOrganizationByOrganizationName(DevFixtureProvisioner.DEV_ORG_NAME))
                .thenReturn(Optional.of(organization(42L)));
        Employee employee = new Employee();
        employee.setId(19L);
        when(employeeRepository.findByUserIdAndOrganizationId(7L, 42L)).thenReturn(Optional.of(employee));
        when(employeeService.assignOrgRole(19L, OrgRole.SYSTEM_ADMIN)).thenAnswer(invocation -> {
            assertThat(TenantContext.getCurrentOrgId())
                    .as("the role assignment must run scoped to the fixture's organization")
                    .isEqualTo(42L);
            return employeeDto(19L);
        });

        provisioner.provision(KEYCLOAK_ID, DISPLAY_NAME, EMAIL);

        assertThat(TenantContext.getCurrentOrgId())
                .as("the startup thread must not be left carrying a tenant")
                .isNull();
    }

    /** A restart must repair the fixture, not duplicate it. */
    @Test
    void isIdempotentWhenEverythingAlreadyExists() {
        when(userRepository.findUserByKeycloakId(KEYCLOAK_ID)).thenReturn(Optional.of(user(7L)));
        when(organizationRepository.findOrganizationByOrganizationName(DevFixtureProvisioner.DEV_ORG_NAME))
                .thenReturn(Optional.of(organization(42L)));
        Employee employee = new Employee();
        employee.setId(19L);
        when(employeeRepository.findByUserIdAndOrganizationId(7L, 42L)).thenReturn(Optional.of(employee));
        when(employeeService.assignOrgRole(19L, OrgRole.SYSTEM_ADMIN)).thenReturn(employeeDto(19L));

        provisioner.provision(KEYCLOAK_ID, DISPLAY_NAME, EMAIL);

        verify(userRepository, never()).save(any(User.class));
        verify(organizationRepository, never()).save(any(Organization.class));
        verify(keycloakGroupService, never()).createOrganizationGroup(any(), any());
        verify(employeeService, never()).joinOrganization(anyLong(), anyLong(), any(EmployeeJoinOrgDto.class));
        // The role assignment is itself idempotent and is reapplied, so a membership removed by hand
        // comes back on the next restart.
        verify(employeeService).assignOrgRole(19L, OrgRole.SYSTEM_ADMIN);
    }

    /** Without a Keycloak id nothing can be bound to the login, so nothing is written. */
    @Test
    void writesNothingWithoutAKeycloakId() {
        provisioner.provision(null, DISPLAY_NAME, EMAIL);

        verify(userRepository, never()).save(any(User.class));
        verify(organizationRepository, never()).save(any(Organization.class));
        verify(employeeService, never()).joinOrganization(anyLong(), anyLong(), any(EmployeeJoinOrgDto.class));
    }
}
