package org.tornotron.echno_backend.modules.sitenotes.job;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.multitenancy.WithoutTenant;
import org.tornotron.echno_backend.modules.sitenotes.SiteNotesModule;
import org.tornotron.echno_backend.modules.sitenotes.SiteNotesModuleEnabled;
import org.tornotron.echno_backend.modules.sitenotes.api.SiteNoteMissingEvent;
import org.tornotron.echno_backend.modules.sitenotes.repository.SiteNoteRepository;

/**
 * The morning reminder: for every organization entitled to the module, which open projects had
 * no site note yesterday. It logs one line per project and publishes a
 * {@link SiteNoteMissingEvent} for anything that wants to tell someone.
 *
 * <p>The pass belongs to no tenant, so it reads scalars only (organization ids and their active
 * flag) before pinning one organization at a time through the runner. The module's kill switch
 * stops it with the endpoints; an organization without the entitlement, or one that has gone
 * dark, is skipped rather than reminded about a module it cannot open.
 */
@Slf4j
@Component
@SiteNotesModuleEnabled
public class SiteNoteReminderJob {

    static final String CRON_PROPERTY = "echno.modules.site-notes.reminder.cron";
    static final String ZONE_PROPERTY = "echno.modules.site-notes.reminder.zone";
    static final String DEFAULT_CRON = "0 45 7 * * *";
    static final int ORGANIZATION_SCAN_LIMIT = 1000;
    static final int PROJECTS_PER_ORGANIZATION = 500;

    private final SiteNoteRepository notes;
    private final ModuleRegistry moduleRegistry;
    private final TenantScopedJobRunner tenantScopedJobRunner;
    private final ApplicationEventPublisher events;
    private final ZoneId zone;

    public SiteNoteReminderJob(SiteNoteRepository notes,
                               ModuleRegistry moduleRegistry,
                               TenantScopedJobRunner tenantScopedJobRunner,
                               ApplicationEventPublisher events,
                               @Value("${" + ZONE_PROPERTY + ":UTC}") String zone) {
        this.notes = notes;
        this.moduleRegistry = moduleRegistry;
        this.tenantScopedJobRunner = tenantScopedJobRunner;
        this.events = events;
        this.zone = ZoneId.of(zone);
    }

    @Scheduled(cron = "${" + CRON_PROPERTY + ":" + DEFAULT_CRON + "}", zone = "${" + ZONE_PROPERTY + ":UTC}")
    @WithoutTenant("The reminder belongs to no organization: it exists to find which organizations "
            + "are entitled to the module, reads their ids and active flags and nothing else at that "
            + "level, and establishes a tenant per organization before reading a project or a note")
    public void remind() {
        try {
            runPass(LocalDate.now(zone).minusDays(1));
        } catch (Exception e) {
            log.error("Site note reminder pass failed: {}", e.getMessage(), e);
        }
    }

    /**
     * One pass over every organization for one day.
     *
     * @return The number of organizations reminded, for the log line and the test.
     */
    public int runPass(LocalDate day) {
        List<SiteNoteRepository.OrganizationRow> organizations = allPages(
                page -> notes.findOrganizationsForReminder(PageRequest.of(page, ORGANIZATION_SCAN_LIMIT)),
                ORGANIZATION_SCAN_LIMIT);
        int reminded = 0;
        int dark = 0;
        int notEntitled = 0;
        int missing = 0;
        for (SiteNoteRepository.OrganizationRow organization : organizations) {
            Long orgId = organization.getId();
            if (!Boolean.TRUE.equals(organization.getIsActive())) {
                dark++;
                continue;
            }
            if (!moduleRegistry.isEnabledForOrg(SiteNotesModule.ID, orgId)) {
                notEntitled++;
                continue;
            }
            try {
                missing += tenantScopedJobRunner.callForTenant(orgId, () -> remindOrganization(orgId, day));
                reminded++;
            } catch (Exception e) {
                log.error("Site note reminder could not look at organization {}: {}", orgId, e.getMessage(), e);
            }
        }
        log.info("Site note reminder for {} looked at {} organization(s): {} reminded, {} without a note on "
                        + "an open project, {} not entitled, {} inactive",
                day, organizations.size(), reminded, missing, notEntitled, dark);
        return reminded;
    }

    private int remindOrganization(Long orgId, LocalDate day) {
        Set<Long> covered = new HashSet<>(notes.findProjectIdsWithNoteOn(day));
        int missing = 0;
        List<SiteNoteRepository.OpenProjectRow> projects = allPages(
                page -> notes.findOpenProjects(PageRequest.of(page, PROJECTS_PER_ORGANIZATION)),
                PROJECTS_PER_ORGANIZATION);
        for (SiteNoteRepository.OpenProjectRow project : projects) {
            if (covered.contains(project.getId())) {
                continue;
            }
            missing++;
            log.info("Site note missing: organization {} project {} ({}) has no note on {}",
                    orgId, project.getId(), project.getProjectName(), day);
            events.publishEvent(new SiteNoteMissingEvent(orgId, project.getId(), project.getProjectName(), day));
        }
        return missing;
    }

    // Walks a scalar scan page by page until a short page, so an organization or a project
    // past the first page is looked at too; the page size only bounds one round trip.
    public static <T> List<T> allPages(IntFunction<List<T>> page, int pageSize) {
        List<T> all = new ArrayList<>();
        for (int n = 0; ; n++) {
            List<T> rows = page.apply(n);
            all.addAll(rows);
            if (rows.size() < pageSize) {
                return all;
            }
        }
    }
}
