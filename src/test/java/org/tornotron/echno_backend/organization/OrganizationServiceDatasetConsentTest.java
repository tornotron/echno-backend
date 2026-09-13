package org.tornotron.echno_backend.organization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.EmployeeService;
import org.tornotron.echno_backend.common.service.KeycloakGroupService;
import org.tornotron.echno_backend.organization.dto.DatasetConsentDto;
import org.tornotron.echno_backend.organization.mapper.OrganizationMapper;
import org.tornotron.echno_backend.user.UserContextService;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The consent setter writes exactly the value it was given, and nothing else (#790). */
@ExtendWith(MockitoExtension.class)
class OrganizationServiceDatasetConsentTest {

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
    @Mock private PayloadValidator payloadValidator;

    @InjectMocks private OrganizationService service;

    @Test
    void setDatasetConsent_persistsTheGivenValueAndReportsIt() {
        Organization org = new Organization();
        org.setId(7L);
        when(userContextService.getCurrentUserId()).thenReturn(3L);
        when(repository.findById(7L)).thenReturn(Optional.of(org));
        when(repository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

        DatasetConsentDto on = service.setDatasetConsent(7L, true);
        assertThat(on.datasetConsent()).isTrue();
        assertThat(org.isDatasetConsent()).isTrue();

        DatasetConsentDto off = service.setDatasetConsent(7L, false);
        assertThat(off.datasetConsent()).isFalse();
        assertThat(org.isDatasetConsent()).isFalse();
        verify(repository, times(2)).save(org);
    }

    @Test
    void setDatasetConsent_refusesAnUnidentifiedCallerBeforeTouchingTheRow() {
        // No local user and no subject: nothing is read or written, and the refusal is explicit.
        when(userContextService.getCurrentUserId()).thenReturn(null);
        when(userContextService.getCurrentKeycloakId()).thenReturn(null);

        assertThatThrownBy(() -> service.setDatasetConsent(7L, true))
                .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void setDatasetConsent_isNotFoundForAnUnknownOrganization() {
        when(userContextService.getCurrentUserId()).thenReturn(3L);
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.setDatasetConsent(99L, true))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
