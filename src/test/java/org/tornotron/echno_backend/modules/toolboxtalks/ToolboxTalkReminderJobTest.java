package org.tornotron.echno_backend.modules.toolboxtalks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
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
    private final ToolboxTalkReminderJob job = new ToolboxTalkReminderJob(talks, registry, runner, events, "UTC");

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
        verify(runner).callForTenant(eq(1L), any());
        verify(runner, never()).callForTenant(eq(2L), any());
        verify(runner, never()).callForTenant(eq(3L), any());
        verify(registry, never()).isEnabledForOrg(ToolboxTalksModule.ID, 2L);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isEqualTo(new ToolboxTalkMissingEvent(1L, 11L, "Tower B", DAY));
    }

    @Test
    void anOrganizationWhoseRunFailsDoesNotStopTheOthers() {
        when(talks.findOrganizationsForReminder(any())).thenReturn(List.of(org(1L, true), org(2L, true)));
        when(registry.isEnabledForOrg(eq(ToolboxTalksModule.ID), anyLong())).thenReturn(true);
        when(runner.callForTenant(eq(1L), any())).thenThrow(new IllegalStateException("db away"));
        when(runner.callForTenant(eq(2L), any())).thenReturn(0);

        assertThat(job.runPass(DAY)).isEqualTo(1);
        verify(runner).callForTenant(eq(2L), any());
    }

    @Test
    void theKillSwitchRemovesTheJobBean() {
        ApplicationContextRunner contexts = new ApplicationContextRunner()
                .withBean(ToolboxTalkRepository.class, () -> talks)
                .withBean(ModuleRegistry.class, () -> registry)
                .withBean(TenantScopedJobRunner.class, () -> runner)
                .withUserConfiguration(ToolboxTalkReminderJob.class);

        contexts.run(ctx -> assertThat(ctx).hasSingleBean(ToolboxTalkReminderJob.class));
        contexts.withPropertyValues(ToolboxTalksModuleEnabled.PROPERTY + "=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ToolboxTalkReminderJob.class));
    }

    @SuppressWarnings("unchecked")
    private void runWorkInline() {
        when(runner.callForTenant(anyLong(), any())).thenAnswer(invocation ->
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
