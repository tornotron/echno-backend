package org.tornotron.echno_backend.modules.inspections.observation;

import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.multitenancy.WithoutTenant;
import org.tornotron.echno_backend.modules.inspections.InspectionsModuleEnabled;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationIntakeUploadRepository;
import org.tornotron.echno_backend.modules.inspections.service.ObservationIntakeUploadService;

/**
 * Daily, deletes producer uploads that no finding ever cited, once they are a week old. A producer
 * that uploads and then fails to post would otherwise leave images nobody can see or reach.
 */
@Slf4j
@Component
@InspectionsModuleEnabled
public class ObservationIntakeUploadSweep {

    static final int ORGANIZATIONS_PER_PAGE = 200;

    private final ObservationIntakeUploadRepository uploads;
    private final ObservationIntakeUploadService service;
    private final TenantScopedJobRunner tenantScopedJobRunner;
    private final int retentionDays;
    private final int maxPerOrganization;

    public ObservationIntakeUploadSweep(
            ObservationIntakeUploadRepository uploads,
            ObservationIntakeUploadService service,
            TenantScopedJobRunner tenantScopedJobRunner,
            @Value("${echno.inspections.intake-upload-sweep.retention-days:7}") int retentionDays,
            @Value("${echno.inspections.intake-upload-sweep.max-per-organization:500}") int maxPerOrganization) {
        this.uploads = uploads;
        this.service = service;
        this.tenantScopedJobRunner = tenantScopedJobRunner;
        this.retentionDays = retentionDays;
        this.maxPerOrganization = maxPerOrganization;
    }

    @Scheduled(cron = "${echno.inspections.intake-upload-sweep.cron:0 30 2 * * *}", zone = "UTC")
    @WithoutTenant("The sweep belongs to no organization: it lists the organizations holding an intake upload, "
            + "reading only their ids, and establishes a tenant for each before reading or deleting anything")
    public void sweep() {
        try {
            runPass(LocalDateTime.now().minusDays(retentionDays));
        } catch (Exception e) {
            log.error("Intake upload sweep failed: {}", e.getMessage(), e);
        }
    }

    /** @return how many images were deleted, across every organization */
    public int runPass(LocalDateTime cutoff) {
        int deleted = 0;
        for (int page = 0; ; page++) {
            List<Long> organizations = uploads.findOrganizationIdsWithUploads(PageRequest.of(page, ORGANIZATIONS_PER_PAGE));
            for (Long orgId : organizations) {
                try {
                    deleted += tenantScopedJobRunner.callForTenantInTransaction(orgId,
                            () -> service.deleteUnreferenced(cutoff, maxPerOrganization));
                } catch (RuntimeException e) {
                    log.error("Intake upload sweep failed for organization {}: {}", orgId, e.getMessage(), e);
                }
            }
            if (organizations.size() < ORGANIZATIONS_PER_PAGE) {
                break;
            }
        }
        log.info("Intake upload sweep deleted {} image(s) older than {}", deleted, cutoff);
        return deleted;
    }
}
