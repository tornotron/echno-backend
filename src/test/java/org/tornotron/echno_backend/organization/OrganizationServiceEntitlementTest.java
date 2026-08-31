package org.tornotron.echno_backend.organization;

import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.billing.dto.FeatureAccessResultDto;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.common.exception.SubscriptionAccessDeniedException;
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

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.tornotron.echno_backend.common.payload.PayloadValidator;

/**
 * The billing rule on creating an organization.
 *
 * <p>A self-registered account holds no subscription and belongs to nothing, so charging it for the
 * organization it needs in order to do anything at all is a closed loop: the account cannot act and
 * cannot buy its way out. Creating the first organization is therefore treated as part of signing
 * up. Everything after the first still goes through the feature check, which is what these tests
 * hold apart.
 */
@ExtendWith(MockitoExtension.class)
class OrganizationServiceEntitlementTest {

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
    private static final String CREATE_ORGANIZATION = "CREATE_ORGANIZATION";

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private OrganizationService service() {
        return new OrganizationService(repository, attachmentService, fileStorageService,
                keycloakGroupService, subscriptionService, userContextService, employeeService,
                organizationMapper, orgSecurity, onboardingSeeder,
                new PayloadValidator(Validation.buildDefaultValidatorFactory().getValidator()));
    }

    private OrganizationCreationDto creationDto() {
        OrganizationCreationDto dto = new OrganizationCreationDto();
        dto.setOrganizationName("Acme Builders");
        dto.setOrganizationEmail("acme@example.test");
        dto.setOrganizationAddress("1 Site Road");
        dto.setOrganizationPhone("0000000000");
        return dto;
    }

    /** Stubs the collaborators a creation needs once it is past the entitlement check. */
    private void stubSuccessfulCreation() {
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
    }

    private User creator() {
        User creator = mock(User.class);
        when(creator.getId()).thenReturn(CREATOR_USER_ID);
        when(userContextService.getCurrentUser()).thenReturn(creator);
        return creator;
    }

    @Test
    void firstOrganizationIsCreatedWithoutAnySubscriptionCheck() {
        // Aneesh's case: registered through the public page, holds no subscription, owns nothing.
        // Before this, the entitlement check refused him with 402 "No active subscription" and
        // onboarding had nowhere left to go.
        creator();
        when(repository.existsByCreatorId(CREATOR_USER_ID.intValue())).thenReturn(false);
        stubSuccessfulCreation();

        service().addOrganization(creationDto(), null);

        verify(repository).save(any(Organization.class));
        verify(subscriptionService, never()).checkFeatureAccess(any(), any());
    }

    @Test
    void firstOrganizationIsStillCountedAgainstTheQuota() {
        // Exempt from the check is not the same as invisible: the meter must still show it, or the
        // second organization looks like the first.
        creator();
        when(repository.existsByCreatorId(CREATOR_USER_ID.intValue())).thenReturn(false);
        stubSuccessfulCreation();

        service().addOrganization(creationDto(), null);

        verify(subscriptionService).recordUsage(CREATOR_USER_ID, CREATE_ORGANIZATION, 1L);
    }

    @Test
    void aSecondOrganizationIsRefusedWhenThePlanDoesNotCoverIt() {
        creator();
        when(repository.existsByCreatorId(CREATOR_USER_ID.intValue())).thenReturn(true);
        when(repository.existsByOrganizationEmail(any())).thenReturn(false);
        when(subscriptionService.checkFeatureAccess(CREATOR_USER_ID, CREATE_ORGANIZATION))
                .thenReturn(FeatureAccessResultDto.noSubscription());

        assertThatExceptionOfType(SubscriptionAccessDeniedException.class)
                .isThrownBy(() -> service().addOrganization(creationDto(), null))
                .satisfies(ex -> {
                    // The refusal names the entitlement, so support can tell which one to look at.
                    org.assertj.core.api.Assertions.assertThat(ex.getFeatureCode())
                            .isEqualTo(CREATE_ORGANIZATION);
                    // The message used to be the raw reason, "No active subscription". True, and
                    // no use to someone who has never been asked to subscribe to anything and is
                    // looking at a button that stopped working. It has to say what governs the
                    // decision. See #642.
                    org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                            .contains("needs a plan")
                            .contains("first organization is always free");
                });

        // Nothing was created, and no Keycloak group was left behind for an org that does not exist.
        verify(repository, never()).save(any(Organization.class));
        verifyNoInteractions(keycloakGroupService);
    }

    @Test
    void aRefusalOnAQuotaNamesTheLimitAndWhatHasBeenUsedAgainstIt() {
        // The case once a plan is actually granted, which is what the seeded catalogue makes
        // possible. A caller who has used their allowance needs to be told the number, not handed
        // the bare reason "Quota exceeded".
        creator();
        when(repository.existsByCreatorId(CREATOR_USER_ID.intValue())).thenReturn(true);
        when(repository.existsByOrganizationEmail(any())).thenReturn(false);
        when(subscriptionService.checkFeatureAccess(CREATOR_USER_ID, CREATE_ORGANIZATION))
                .thenReturn(FeatureAccessResultDto.quotaExceeded(3L, 3L));

        assertThatExceptionOfType(SubscriptionAccessDeniedException.class)
                .isThrownBy(() -> service().addOrganization(creationDto(), null))
                .satisfies(ex -> {
                    org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                            .contains("covers 3 organizations")
                            .contains("already created 3");
                    // The numbers still travel on the result for the frontend to render.
                    org.assertj.core.api.Assertions.assertThat(ex.getQuotaLimit()).isEqualTo(3L);
                    org.assertj.core.api.Assertions.assertThat(ex.getCurrentUsage()).isEqualTo(3L);
                });

        verify(repository, never()).save(any(Organization.class));
    }

    @Test
    void aRefusalOnASingleOrganizationLimitReadsAsSingular() {
        // The free tier's limit is 1, so this is the wording most callers will actually meet.
        creator();
        when(repository.existsByCreatorId(CREATOR_USER_ID.intValue())).thenReturn(true);
        when(repository.existsByOrganizationEmail(any())).thenReturn(false);
        when(subscriptionService.checkFeatureAccess(CREATOR_USER_ID, CREATE_ORGANIZATION))
                .thenReturn(FeatureAccessResultDto.quotaExceeded(1L, 1L));

        assertThatExceptionOfType(SubscriptionAccessDeniedException.class)
                .isThrownBy(() -> service().addOrganization(creationDto(), null))
                .withMessageContaining("covers 1 organization and")
                .withMessageNotContaining("1 organizations");
    }

    @Test
    void aSecondOrganizationIsCreatedWhenThePlanCoversIt() {
        creator();
        when(repository.existsByCreatorId(CREATOR_USER_ID.intValue())).thenReturn(true);
        when(subscriptionService.checkFeatureAccess(CREATOR_USER_ID, CREATE_ORGANIZATION))
                .thenReturn(FeatureAccessResultDto.allowed());
        stubSuccessfulCreation();

        service().addOrganization(creationDto(), null);

        verify(repository).save(any(Organization.class));
        verify(subscriptionService).recordUsage(CREATOR_USER_ID, CREATE_ORGANIZATION, 1L);
    }

    @Test
    void anUnprovisionedAccountIsRefusedRatherThanFailingOnANullUser() {
        // Authenticated against Keycloak but with no local user record yet. Reading the id off
        // null would have surfaced as a 500 with nothing in it for the caller.
        when(userContextService.getCurrentUser()).thenReturn(null);
        when(repository.existsByOrganizationEmail(any())).thenReturn(false);

        assertThatExceptionOfType(org.springframework.security.access.AccessDeniedException.class)
                .isThrownBy(() -> service().addOrganization(creationDto(), null))
                .withMessageContaining("not provisioned yet");

        verify(repository, never()).save(any(Organization.class));
    }
}
