package org.tornotron.echno_backend.organization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.service.KeycloakGroupService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.employee.EmployeeService;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.organization.mapper.OrganizationMapper;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the row filtering behind the organization picker.
 *
 * <p>These matter because the endpoint guard for this list is authentication alone. The
 * narrowing to the caller's own organizations therefore has to come from here, and these tests
 * are what make that load bearing: the service queries by the authenticated user's identity and
 * never lists organizations wholesale. Plain Mockito with no Spring context, so it adds nothing
 * to the cached-context heap budget.
 */
@ExtendWith(MockitoExtension.class)
class OrganizationServiceScopingTest {

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

    @InjectMocks private OrganizationService service;

    @Test
    void getAllOrganization_queriesByTheAuthenticatedUsersOwnIdentity() {
        User caller = new User();
        caller.setEmail("member@echno.com");
        when(userContextService.getCurrentUser()).thenReturn(caller);

        Organization owned = new Organization();
        owned.setId(7L);
        when(repository.findAllByUserEmail("member@echno.com")).thenReturn(List.of(owned));

        OrganizationDto dto = new OrganizationDto();
        dto.setId(7L);
        when(organizationMapper.toDto(owned)).thenReturn(dto);

        List<OrganizationDto> result = service.getAllOrganization();

        assertThat(result).extracting(OrganizationDto::getId).containsExactly(7L);
        // The filter is the caller's identity. Nothing here lists organizations wholesale,
        // so relaxing the endpoint guard cannot widen the result set.
        verify(repository).findAllByUserEmail("member@echno.com");
        verify(repository, never()).findAll();
    }

    @Test
    void getAllOrganization_isEmptyWhenThereIsNoLocalUserRecord() {
        // Authenticated against Keycloak but not yet provisioned locally. The old role guard
        // hid this path; with the relaxed guard it is reachable and must not blow up.
        when(userContextService.getCurrentUser()).thenReturn(null);

        List<OrganizationDto> result = service.getAllOrganization();

        assertThat(result).isEmpty();
        verify(repository, never()).findAllByUserEmail(any());
    }
}
