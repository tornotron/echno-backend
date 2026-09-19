package org.tornotron.echno_backend.modules.toolboxtalks.job;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.multitenancy.WithoutTenant;
import org.tornotron.echno_backend.modules.toolboxtalks.ToolboxTalksModule;
import org.tornotron.echno_backend.modules.toolboxtalks.ToolboxTalksModuleEnabled;
import org.tornotron.echno_backend.modules.toolboxtalks.api.ToolboxTalkMissingEvent;
import org.tornotron.echno_backend.modules.toolboxtalks.repository.ToolboxTalkRepository;

/**
 * The morning reminder: for every organization entitled to the module, which open projects
 * had no talk recorded yesterday. It logs one line per project and publishes a
 * {@link ToolboxTalkMissingEvent} for anything that wants to tell someone.
 *
 * <p>The pass belongs to no tenant, so it reads scalars only (organization ids and their
 * active flag) before pinning one organization at a time through the runner. The module's
 * kill switch stops it with the endpoints; an organization without the entitlement, or one
 * that has gone dark, is skipped rather than reminded about a module it cannot open.
 */
@Slf4j
@Component
@ToolboxTalksModuleEnabled
public class ToolboxTalkReminderJob {

    static final String CRON_PROPERTY = "echno.modules.toolbox-talks.reminder.cron";
    static final String ZONE_PROPERTY = "echno.modules.toolbox-talks.reminder.zone";
    static final String DEFAULT_CRON = "0 30 7 * * *";
    static final int ORGANIZATION_SCAN_LIMIT = 1000;
    static final int PROJECTS_PER_ORGANIZATION = 500;

    private final ToolboxTalkRepository talks;
    private final ModuleRegistry moduleRegistry;
    private final TenantScopedJobRunner tenantScopedJobRunner;
    private final ApplicationEventPublisher events;
    private final ZoneId zone;

    public ToolboxTalkReminderJob(ToolboxTalkRepository talks,
                                  ModuleRegistry moduleRegistry,
                                  TenantScopedJobRunner tenantScopedJobRunner,
                                  ApplicationEventPublisher events,
                                  @Value("${" + ZONE_PROPERTY + ":UTC}") String zone) {
        this.talks = talks;
        this.moduleRegistry = moduleRegistry;
        this.tenantScopedJobRunner = tenantScopedJobRunner;
        this.events = events;
        this.zone = ZoneId.of(zone);
    }

    @Scheduled(cron = "${" + CRON_PROPERTY + ":" + DEFAULT_CRON + "}", zone = "${" + ZONE_PROPERTY + ":UTC}")
    @WithoutTenant("The reminder belongs to no organization: it exists to find which organizations "
            + "are entitled to the module, reads their ids and active flags and nothing else at that "
            + "level, and establishes a tenant per organization before reading a project or a talk")
    public void remind() {
        try {
            runPass(LocalDate.now(zone).minusDays(1));
        } catch (Exception e) {
            log.error("Toolbox talk reminder pass failed: {}", e.getMessage(), e);
        }
    }

    /**
     * One pass over every organization for one day.
     *
     * @return The number of organizations reminded, for the log line and the test.
     */
    public int runPass(LocalDate day) {
        List<ToolboxTalkRepository.OrganizationRow> organizations =
                talks.findOrganizationsForReminder(PageRequest.of(0, ORGANIZATION_SCAN_LIMIT));
        int reminded = 0;
        int dark = 0;
        int notEntitled = 0;
        int missing = 0;
        for (ToolboxTalkRepository.OrganizationRow organization : organizations) {
            Long orgId = organization.getId();
            if (!Boolean.TRUE.equals(organization.getIsActive())) {
                dark++;
                continue;
            }
            if (!moduleRegistry.isEnabledForOrg(ToolboxTalksModule.ID, orgId)) {
                notEntitled++;
                continue;
            }
            try {
                missing += tenantScopedJobRunner.callForTenant(orgId, () -> remindOrganization(orgId, day));
                reminded++;
            } catch (Exception e) {
                log.error("Toolbox talk reminder could not look at organization {}: {}", orgId, e.getMessage(), e);
            }
        }
        log.info("Toolbox talk reminder for {} looked at {} organization(s): {} reminded, {} without a talk on "
                        + "an open project, {} not entitled, {} inactive",
                day, organizations.size(), reminded, missing, notEntitled, dark);
        return reminded;
    }

    private int remindOrganization(Long orgId, LocalDate day) {
        Set<Long> covered = new HashSet<>(talks.findProjectIdsWithRecordedTalkOn(day));
        int missing = 0;
        for (ToolboxTalkRepository.OpenProjectRow project
                : talks.findOpenProjects(PageRequest.of(0, PROJECTS_PER_ORGANIZATION))) {
            if (covered.contains(project.getId())) {
                continue;
            }
            missing++;
            log.info("Toolbox talk missing: organization {} project {} ({}) recorded no talk on {}",
                    orgId, project.getId(), project.getProjectName(), day);
            events.publishEvent(new ToolboxTalkMissingEvent(orgId, project.getId(), project.getProjectName(), day));
        }
        return missing;
    }
}
