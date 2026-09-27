package org.tornotron.echno_backend.modules.toolboxtalks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.modules.toolboxtalks.api.ToolboxTalkMissingEvent;
import org.tornotron.echno_backend.modules.toolboxtalks.job.ToolboxTalkReminderJob;
import org.tornotron.echno_backend.modules.toolboxtalks.repository.ToolboxTalkRepository;
import org.tornotron.echno_backend.modules.toolboxtalks.time.ToolboxTalksClockConfiguration;

/**
 * The reminder pins only the organizations it should: inactive ones and ones without the
 * module are skipped before any tenant is established, and the missing-talk event names the
 * project that had no record.
 */
class ToolboxTalkReminderJobTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 18);

    private final ToolboxTalkRepository talks = mock(ToolboxTalkRepository.class);
    private final ModuleRegistry registry = mock(ModuleRegistry.class);
    private final TenantScopedJobRunner runner = mock(TenantScopedJobRunner.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    // 20:00 UTC on the 18th is 01:30 IST on the 19th: the window where the server's date and the
    // sites' date disagree (#879).
    private static final Clock IST_AFTER_MIDNIGHT =
            Clock.fixed(Instant.parse("2026-09-18T20:00:00Z"), ZoneId.of("Asia/Kolkata"));

    private final ToolboxTalkReminderJob job =
            new ToolboxTalkReminderJob(talks, registry, runner, events, IST_AFTER_MIDNIGHT);

    @Test
    void skipsInactiveAndNonEntitledOrganizationsAndRemindsTheRest() {
        when(talks.findOrganizationsForReminder(any())).thenReturn(List.of(
                org(1L, true), org(2L, false), org(3L, true)));
        when(registry.isEnabledForOrg(ToolboxTalksModule.ID, 1L)).thenReturn(true);
        when(registry.isEnabledForOrg(ToolboxTalksModule.ID, 3L)).thenReturn(false);
        runWorkInline();
        when(talks.findOpenProjects(any())).thenReturn(List.of(project(10L, "Tower A"), project(11L, "Tower B")));
        when(talks.findProjectIdsWithRecordedTalkOn(DAY)).thenReturn(List.of(10L));

        int reminded = job.runPass(DAY);

        assertThat(reminded).isEqualTo(1);
        verify(runner).callForTenantInTransaction(eq(1L), any());
        verify(runner, never()).callForTenantInTransaction(eq(2L), any());
        verify(runner, never()).callForTenantInTransaction(eq(3L), any());
        verify(runner, never()).callForTenant(any(), any());
        verify(registry, never()).isEnabledForOrg(ToolboxTalksModule.ID, 2L);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isEqualTo(new ToolboxTalkMissingEvent(1L, 11L, "Tower B", DAY));
    }

    @Test
    void theScheduledPassRemindsAboutTheSitesYesterdayNotTheServers() {
        when(talks.findOrganizationsForReminder(any())).thenReturn(List.of(org(1L, true)));
        when(registry.isEnabledForOrg(ToolboxTalksModule.ID, 1L)).thenReturn(true);
        runWorkInline();
        when(talks.findOpenProjects(any())).thenReturn(List.of());

        job.remind();

        // The sites' today is the 19th, so yesterday is the 18th; the server's UTC date would
        // have made it the 17th.
        verify(talks).findProjectIdsWithRecordedTalkOn(LocalDate.of(2026, 9, 18));
    }

    @Test
    void walksEveryPageOfAScanUntilAShortOne() {
        List<Integer> asked = new java.util.ArrayList<>();
        List<Integer> rows = ToolboxTalkReminderJob.allPages(page -> {
            asked.add(page);
            return page < 2 ? List.of(page, page) : List.of(page);
        }, 2);

        assertThat(asked).containsExactly(0, 1, 2);
        assertThat(rows).containsExactly(0, 0, 1, 1, 2);
    }

    @Test
    void anOrganizationWhoseRunFailsDoesNotStopTheOthers() {
        when(talks.findOrganizationsForReminder(any())).thenReturn(List.of(org(1L, true), org(2L, true)));
        when(registry.isEnabledForOrg(eq(ToolboxTalksModule.ID), anyLong())).thenReturn(true);
        when(runner.callForTenantInTransaction(eq(1L), any())).thenThrow(new IllegalStateException("db away"));
        when(runner.callForTenantInTransaction(eq(2L), any())).thenReturn(0);

        assertThat(job.runPass(DAY)).isEqualTo(1);
        verify(runner).callForTenantInTransaction(eq(2L), any());
    }

    @Test
    void theKillSwitchRemovesTheJobBean() {
        ApplicationContextRunner contexts = new ApplicationContextRunner()
                .withBean(ToolboxTalkRepository.class, () -> talks)
                .withBean(ModuleRegistry.class, () -> registry)
                .withBean(TenantScopedJobRunner.class, () -> runner)
                .withUserConfiguration(ToolboxTalksClockConfiguration.class, ToolboxTalkReminderJob.class);

        contexts.run(ctx -> assertThat(ctx).hasSingleBean(ToolboxTalkReminderJob.class));
        contexts.withPropertyValues(ToolboxTalksModuleEnabled.PROPERTY + "=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ToolboxTalkReminderJob.class));
    }

    @SuppressWarnings("unchecked")
    private void runWorkInline() {
        when(runner.callForTenantInTransaction(anyLong(), any())).thenAnswer(invocation ->
                ((Supplier<Object>) invocation.getArgument(1)).get());
    }

    private static ToolboxTalkRepository.OrganizationRow org(Long id, Boolean active) {
        return new ToolboxTalkRepository.OrganizationRow() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public Boolean getIsActive() {
                return active;
            }
        };
    }

    private static ToolboxTalkRepository.OpenProjectRow project(Long id, String name) {
        return new ToolboxTalkRepository.OpenProjectRow() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public String getProjectName() {
                return name;
            }
        };
    }
}
