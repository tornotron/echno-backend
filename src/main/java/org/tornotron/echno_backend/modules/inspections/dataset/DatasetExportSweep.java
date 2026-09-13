package org.tornotron.echno_backend.modules.inspections.dataset;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.multitenancy.WithoutTenant;
import org.tornotron.echno_backend.modules.inspections.InspectionsModule;
import org.tornotron.echno_backend.modules.inspections.InspectionsModuleEnabled;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;

/**
 * The scheduled half of the evidence export (#791): one run per consenting organization, each
 * under its own tenant context. Off unless {@code echno.dataset-export.enabled} is true, and off
 * with the inspections module's kill switch, the way the compliance sweep is.
 *
 * <p>The organization list is the one cross-tenant read, on the organization table, which is the
 * tenant root and carries no filter; everything after that runs inside
 * {@link TenantScopedJobRunner#runForTenant}. A run that fails is recorded on its own row by the
 * service and does not stop the others.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "echno.dataset-export.enabled", havingValue = "true")
@InspectionsModuleEnabled
public class DatasetExportSweep {

    private final OrganizationRepository organizationRepository;
    private final DatasetExportService exportService;
    private final TenantScopedJobRunner tenantScopedJobRunner;
    private final ModuleRegistry moduleRegistry;

    @Scheduled(cron = "${echno.dataset-export.cron:0 0 3 * * SUN}", zone = "${echno.dataset-export.zone:UTC}")
    @WithoutTenant("The sweep belongs to no organization: it lists the organizations that have "
            + "recorded dataset consent, a column on the tenant root, and establishes a tenant "
            + "for each before anything is read or copied")
    public void sweep() {
        try {
            runPass();
        } catch (Exception e) {
            log.error("Dataset export sweep failed: {}", e.getMessage(), e);
        }
    }

    void runPass() {
        int started = 0;
        for (Organization organization : organizationRepository.findByDatasetConsentTrue()) {
            Long orgId = organization.getId();
            if (!moduleRegistry.isEnabledForOrg(InspectionsModule.ID, orgId)) {
                log.debug("Dataset export skipped organization {}: inspections module not enabled for it", orgId);
                continue;
            }
            started++;
            try {
                tenantScopedJobRunner.runForTenant(orgId, () -> exportService.runForOrganization(orgId, "schedule"));
            } catch (RuntimeException e) {
                log.error("Dataset export for organization {} failed: {}", orgId, e.getMessage(), e);
            }
        }
        log.info("Dataset export sweep ran for {} consenting organization(s)", started);
    }
}
