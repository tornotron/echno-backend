package org.tornotron.echno_backend.modules.inspections.dataset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.modules.inspections.InspectionsModule;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sweep runs one export per consenting organization, each under that organization's
 * tenant, and never reads the organization table any other way (#791).
 */
@ExtendWith(MockitoExtension.class)
class DatasetExportSweepTest {

    @Mock private OrganizationRepository organizationRepository;
    @Mock private DatasetExportService exportService;
    @Mock private ModuleRegistry moduleRegistry;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void runsOnlyForConsentingOrganizationsAndUnderEachOnesTenant() {
        Organization a = org(7L);
        Organization b = org(8L);
        when(organizationRepository.findByDatasetConsentTrue()).thenReturn(List.of(a, b));
        lenient().when(moduleRegistry.isEnabledForOrg(eq(InspectionsModule.ID), anyLong())).thenReturn(true);
        List<Long> tenantsSeen = new ArrayList<>();
        when(exportService.runForOrganization(anyLong(), anyString())).thenAnswer(inv -> {
            tenantsSeen.add(TenantContext.getCurrentOrgId());
            return null;
        });

        new DatasetExportSweep(organizationRepository, exportService, new TenantScopedJobRunner(), moduleRegistry)
                .runPass();

        verify(exportService).runForOrganization(7L, "schedule");
        verify(exportService).runForOrganization(8L, "schedule");
        assertThat(tenantsSeen).containsExactly(7L, 8L);
        assertThat(TenantContext.getCurrentOrgId()).isNull();
        // the consent query is the only source of organizations
        verify(organizationRepository, never()).findAll();
    }

    @Test
    void skipsAnOrganizationWhoseInspectionsModuleIsOffAndSurvivesAFailedRun() {
        Organization a = org(7L);
        Organization b = org(8L);
        Organization c = org(9L);
        when(organizationRepository.findByDatasetConsentTrue()).thenReturn(List.of(a, b, c));
        when(moduleRegistry.isEnabledForOrg(InspectionsModule.ID, 7L)).thenReturn(false);
        when(moduleRegistry.isEnabledForOrg(InspectionsModule.ID, 8L)).thenReturn(true);
        when(moduleRegistry.isEnabledForOrg(InspectionsModule.ID, 9L)).thenReturn(true);
        when(exportService.runForOrganization(8L, "schedule")).thenThrow(new IllegalStateException("store down"));

        new DatasetExportSweep(organizationRepository, exportService, new TenantScopedJobRunner(), moduleRegistry)
                .runPass();

        verify(exportService, never()).runForOrganization(eq(7L), anyString());
        verify(exportService).runForOrganization(9L, "schedule");
        assertThat(TenantContext.getCurrentOrgId()).isNull();
    }

    private static Organization org(Long id) {
        Organization o = new Organization();
        o.setId(id);
        o.setDatasetConsent(true);
        return o;
    }
}
