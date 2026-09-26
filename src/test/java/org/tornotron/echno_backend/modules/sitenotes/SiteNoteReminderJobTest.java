package org.tornotron.echno_backend.modules.sitenotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.modules.sitenotes.api.SiteNoteMissingEvent;
import org.tornotron.echno_backend.modules.sitenotes.job.SiteNoteReminderJob;
import org.tornotron.echno_backend.modules.sitenotes.repository.SiteNoteRepository;

/**
 * The reminder pins only the organizations it should: inactive ones and ones without the module
 * are skipped before any tenant is established, the missing-note event names the project that
 * had no note, and the module's kill switch removes the job.
 */
class SiteNoteReminderJobTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 23);

    private final SiteNoteRepository notes = mock(SiteNoteRepository.class);
    private final ModuleRegistry registry = mock(ModuleRegistry.class);
    private final TenantScopedJobRunner runner = mock(TenantScopedJobRunner.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SiteNoteReminderJob job = new SiteNoteReminderJob(notes, registry, runner, events, "UTC");

    @Test
    void skipsInactiveAndNonEntitledOrganizationsAndRemindsTheRest() {
        when(notes.findOrganizationsForReminder(any())).thenReturn(List.of(
                org(1L, true), org(2L, false), org(3L, true)));
        when(registry.isEnabledForOrg(SiteNotesModule.ID, 1L)).thenReturn(true);
        when(registry.isEnabledForOrg(SiteNotesModule.ID, 3L)).thenReturn(false);
        runWorkInline();
        when(notes.findOpenProjects(any())).thenReturn(List.of(project(10L, "Tower A"), project(11L, "Tower B")));
        when(notes.findProjectIdsWithNoteOn(DAY)).thenReturn(List.of(10L));

        int reminded = job.runPass(DAY);

        assertThat(reminded).isEqualTo(1);
        verify(runner).callForTenant(eq(1L), any());
        verify(runner, never()).callForTenant(eq(2L), any());
        verify(runner, never()).callForTenant(eq(3L), any());
        verify(registry, never()).isEnabledForOrg(SiteNotesModule.ID, 2L);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isEqualTo(new SiteNoteMissingEvent(1L, 11L, "Tower B", DAY));
    }

    @Test
    void walksEveryPageOfAScanUntilAShortOne() {
        List<Integer> asked = new ArrayList<>();
        List<Integer> rows = SiteNoteReminderJob.allPages(page -> {
            asked.add(page);
            return page < 2 ? List.of(page, page) : List.of(page);
        }, 2);

        assertThat(asked).containsExactly(0, 1, 2);
        assertThat(rows).containsExactly(0, 0, 1, 1, 2);
    }

    @Test
    void anOrganizationWhoseRunFailsDoesNotStopTheOthers() {
        when(notes.findOrganizationsForReminder(any())).thenReturn(List.of(org(1L, true), org(2L, true)));
        when(registry.isEnabledForOrg(eq(SiteNotesModule.ID), anyLong())).thenReturn(true);
        when(runner.callForTenant(eq(1L), any())).thenThrow(new IllegalStateException("db away"));
        when(runner.callForTenant(eq(2L), any())).thenReturn(0);

        assertThat(job.runPass(DAY)).isEqualTo(1);
        verify(runner).callForTenant(eq(2L), any());
    }

    @Test
    void theKillSwitchRemovesTheJobBean() {
        ApplicationContextRunner contexts = new ApplicationContextRunner()
                .withBean(SiteNoteRepository.class, () -> notes)
                .withBean(ModuleRegistry.class, () -> registry)
                .withBean(TenantScopedJobRunner.class, () -> runner)
                .withUserConfiguration(SiteNoteReminderJob.class);

        contexts.run(ctx -> assertThat(ctx).hasSingleBean(SiteNoteReminderJob.class));
        contexts.withPropertyValues(SiteNotesModuleEnabled.PROPERTY + "=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SiteNoteReminderJob.class));
    }

    @SuppressWarnings("unchecked")
    private void runWorkInline() {
        when(runner.callForTenant(anyLong(), any())).thenAnswer(invocation ->
                ((Supplier<Object>) invocation.getArgument(1)).get());
    }

    private static SiteNoteRepository.OrganizationRow org(Long id, Boolean active) {
        return new SiteNoteRepository.OrganizationRow() {
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

    private static SiteNoteRepository.OpenProjectRow project(Long id, String name) {
        return new SiteNoteRepository.OpenProjectRow() {
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
