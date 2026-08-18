package org.tornotron.echno_backend.organization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.common.exception.TenantAccessDeniedException;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.common.service.KeycloakGroupService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.EmployeeService;
import org.tornotron.echno_backend.organization.dto.OrganizationPatchDto;
import org.tornotron.echno_backend.organization.mapper.OrganizationMapper;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Batch organization update must authorize each id, because Organization is the
 * tenant root (not a TenantScopedEntity) and so is guarded by neither the org
 * filter nor the fail-closed load listener. Without the check a member of one
 * organization could patch another organization by passing its id.
 */
@ExtendWith(MockitoExtension.class)
class OrganizationServiceBatchAuthzTest {

    @Mock private OrganizationRepository repository;
    @Mock private AttachmentService attachmentService;
    @Mock private FileStorageService fileStorageService;
    @Mock private KeycloakGroupService keycloakGroupService;
    @Mock private SubscriptionService subscriptionService;
    @Mock private UserContextService userContextService;
    @Mock private EmployeeService employeeService;
    @Mock private OrganizationMapper organizationMapper;
    @Mock private OrganizationSecurityService orgSecurity;

    private OrganizationService service() {
        return new OrganizationService(repository, attachmentService, fileStorageService,
                keycloakGroupService, subscriptionService, userContextService, employeeService,
                organizationMapper, orgSecurity);
    }

    private OrganizationPatchDto patch(long id) {
        OrganizationPatchDto dto = new OrganizationPatchDto();
        dto.setId(id);
        dto.setUpdates(Map.of("name", "Renamed"));
        return dto;
    }

    @Test
    void rejectsUpdateToAnOrganizationTheCallerCannotAccess() {
        when(orgSecurity.isMemberOrAdmin(2L)).thenReturn(false);

        assertThatExceptionOfType(TenantAccessDeniedException.class)
                .isThrownBy(() -> service().batchUpdateOrganization(List.of(patch(2L))));

        // Rejected before any data is loaded or written.
        verify(repository, never()).findAllById(anyList());
        verify(repository, never()).saveAll(any());
    }

    @Test
    void proceedsWhenTheCallerIsAMemberOrAdmin() {
        when(orgSecurity.isMemberOrAdmin(1L)).thenReturn(true);
        when(repository.findAllById(anyList())).thenReturn(List.of());

        service().batchUpdateOrganization(List.of(patch(1L)));

        // Passed the guard and reached the persistence step.
        verify(repository).saveAll(any());
    }
}
