package org.tornotron.echno_backend.organization;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import jakarta.validation.Validation;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.common.service.KeycloakGroupService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.EmployeeService;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.organization.dto.OrganizationCreationDto;
import org.tornotron.echno_backend.organization.dto.OrganizationSimpleDto;
import org.tornotron.echno_backend.organization.mapper.OrganizationMapper;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserContextService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Self-service onboarding wiring for {@code addOrganization}: the creator of an organization must be
 * made its system-admin, and the organization's finance defaults must be seeded on creation. Both
 * are the core of the self-service path, since without the system-admin grant the creator cannot
 * administer the org they just made, and without the seed the org has no chart, cost categories or
 * finance settings to work with.
 */
@ExtendWith(MockitoExtension.class)
class OrganizationServiceOnboardingTest {

    @Mock private OrganizationRepository repository;
    @Mock private AttachmentService attachmentService;
    @Mock private FileStorageService fileStorageService;
    @Mock private KeycloakGroupService keycloakGroupService;
    @Mock private SubscriptionService subscriptionService;
    @Mock private UserContextService userContextService;
    @Mock private EmployeeService employeeService;
    @Mock private OrganizationMapper organizationMapper;
    @Mock private OrganizationSecurityService orgSecurity;
    @Mock private OrganizationOnboardingSeeder onboardingSeeder;

    private static final Long CREATOR_USER_ID = 7L;
    private static final Long NEW_ORG_ID = 42L;
    private static final Long CREATOR_EMPLOYEE_ID = 5L;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private OrganizationService service() {
        return new OrganizationService(repository, attachmentService, fileStorageService,
                keycloakGroupService, subscriptionService, userContextService, employeeService,
                organizationMapper, orgSecurity, onboardingSeeder,
                Validation.buildDefaultValidatorFactory().getValidator());
    }

    private OrganizationCreationDto creationDto() {
        OrganizationCreationDto dto = new OrganizationCreationDto();
        dto.setOrganizationName("Acme Builders");
        dto.setOrganizationEmail("acme@example.test");
        dto.setOrganizationAddress("1 Site Road");
        dto.setOrganizationPhone("0000000000");
        return dto;
    }

    @Test
    void makesTheCreatorSystemAdminAndSeedsFinanceDefaults() {
        User creator = mock(User.class);
        when(creator.getId()).thenReturn(CREATOR_USER_ID);
        when(userContextService.getCurrentUser()).thenReturn(creator);

        when(repository.existsByOrganizationEmail(any())).thenReturn(false);
        when(repository.save(any(Organization.class))).thenAnswer(invocation -> {
            Organization toSave = invocation.getArgument(0);
            toSave.setId(NEW_ORG_ID);
            return toSave;
        });

        EmployeeDto creatorEmployee = new EmployeeDto();
        creatorEmployee.setId(CREATOR_EMPLOYEE_ID);
        when(employeeService.joinOrganization(eq(CREATOR_USER_ID), eq(NEW_ORG_ID), any()))
                .thenReturn(creatorEmployee);
        when(organizationMapper.toSimpleDto(any())).thenReturn(new OrganizationSimpleDto());

        service().addOrganization(creationDto(), null);

        // The creator is granted the org's system-admin role (which adds them to the
        // '/org-42/system-admin' Keycloak subgroup under the hood).
        verify(employeeService).assignOrgRole(CREATOR_EMPLOYEE_ID, OrgRole.SYSTEM_ADMIN);
        // The org's finance defaults are seeded for the newly created org id. Without an active
        // transaction in this unit test the seeding runs inline rather than after commit.
        verify(onboardingSeeder).seedFinanceDefaults(NEW_ORG_ID);
    }
}
