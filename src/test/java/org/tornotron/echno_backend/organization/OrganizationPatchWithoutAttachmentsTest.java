package org.tornotron.echno_backend.organization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.EmployeeService;
import org.tornotron.echno_backend.common.service.KeycloakGroupService;
import org.tornotron.echno_backend.organization.dto.OrganizationSimpleDto;
import org.tornotron.echno_backend.organization.mapper.OrganizationMapper;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Editing an organization without replacing its logo.
 *
 * <p>The handler declares the attachments part as {@code required = false}, and Spring resolves an
 * absent multipart parameter to null rather than to an empty list. The service then iterated it
 * with no guard, so the loop dereferenced null. Every edit of an organization's name, address,
 * email, phone or website that did not also upload a file went that way, which is nearly all of
 * them. {@code IssueService} guards the same shape correctly; this one did not.
 *
 * <p>Two things are pinned, because either alone proves nothing. That Spring really hands the
 * service a null is the companion web-slice case in
 * {@link OrganizationPatchAttachmentsArgumentTest}; that a null crashes the service is here. The
 * first test fails with a NullPointerException on the unguarded code.
 */
@ExtendWith(MockitoExtension.class)
class OrganizationPatchWithoutAttachmentsTest {

    private static final Long ORG_ID = 7L;

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

    private OrganizationService service() {
        return new OrganizationService(
                repository,
                attachmentService,
                fileStorageService,
                keycloakGroupService,
                subscriptionService,
                userContextService,
                employeeService,
                organizationMapper,
                orgSecurity,
                onboardingSeeder,
                payloadValidator);
    }

    private Organization organization() {
        Organization organization = new Organization();
        organization.setId(ORG_ID);
        organization.setOrganizationName("Asset Homes");
        return organization;
    }

    @Test
    void updatingAnOrganization_withNoAttachmentsPart_doesNotCrash() {
        Organization stored = organization();
        when(repository.findById(ORG_ID)).thenReturn(Optional.of(stored));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(organizationMapper.toSimpleDto(any())).thenReturn(new OrganizationSimpleDto());

        assertThatCode(() -> service().partialUpdateAnOrganization(
                Map.of("organizationName", "Asset Homes Kerala"),
                ORG_ID,
                null,
                "ORGANIZATION_LOGO"))
                .doesNotThrowAnyException();

        assertThatOrganizationWasRenamed(stored);
        // No file arrived, so nothing should have been asked of the attachment store.
        verifyNoInteractions(attachmentService);
    }

    private void assertThatOrganizationWasRenamed(Organization stored) {
        org.assertj.core.api.Assertions.assertThat(stored.getOrganizationName())
                .isEqualTo("Asset Homes Kerala");
    }
}
