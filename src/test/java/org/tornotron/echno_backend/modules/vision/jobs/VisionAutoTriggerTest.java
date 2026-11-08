package org.tornotron.echno_backend.modules.vision.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.modules.inspections.api.EvidenceFile;
import org.tornotron.echno_backend.modules.vision.VisionModule;
import org.tornotron.echno_backend.modules.vision.domain.CvModelVersion;
import org.tornotron.echno_backend.modules.vision.domain.VisionJobTrigger;
import org.tornotron.echno_backend.modules.vision.service.VisionSettingsService;

/**
 * The automatic trigger fires only for an organization that has the module and has auto-inference
 * on, and then for the families its settings name.
 */
class VisionAutoTriggerTest {

    private static final Long ORG = 7L;

    private final ModuleRegistry registry = mock(ModuleRegistry.class);
    private final VisionSettingsService settings = mock(VisionSettingsService.class);
    private final VisionJobQueue queue = mock(VisionJobQueue.class);
    private final VisionAutoTrigger trigger = new VisionAutoTrigger(registry, settings, queue);
    private final EvidenceFile photo = new EvidenceFile(ORG, 41L, UUID.randomUUID(), null, 18342L, "inspection/a.jpg",
            "image/jpeg", LocalDateTime.now());
    private final List<VisionJobQueue.RunnableVersion> ppe =
            List.of(new VisionJobQueue.RunnableVersion("ppe", new CvModelVersion()));

    @Test
    void anEntitledOrganizationWithAutoInferenceOnQueuesItsFamilies() {
        when(registry.isEnabledForOrg(VisionModule.ID, ORG)).thenReturn(true);
        when(settings.autoInferModelKeys()).thenReturn(Optional.of(List.of("ppe")));
        when(queue.activeVersions(List.of("ppe"))).thenReturn(ppe);
        when(queue.queue(List.of(photo), ppe, VisionJobTrigger.AUTO, null, false))
                .thenReturn(new VisionJobQueue.Outcome(1, 0, 0));

        assertThat(trigger.onImage(photo).queued()).isEqualTo(1);
    }

    @Test
    void autoInferenceOffQueuesNothing() {
        when(registry.isEnabledForOrg(VisionModule.ID, ORG)).thenReturn(true);
        when(settings.autoInferModelKeys()).thenReturn(Optional.empty());

        assertThat(trigger.onImage(photo)).isEqualTo(VisionJobQueue.Outcome.NONE);
        verify(queue, never()).queue(anyList(), anyList(), any(), any(), anyBoolean());
    }

    @Test
    void anOrganizationWithoutTheModuleQueuesNothingWhateverItsSettings() {
        when(registry.isEnabledForOrg(VisionModule.ID, ORG)).thenReturn(false);
        when(settings.autoInferModelKeys()).thenReturn(Optional.of(List.of()));

        assertThat(trigger.onImage(photo)).isEqualTo(VisionJobQueue.Outcome.NONE);
        verify(settings, never()).autoInferModelKeys();
        verify(queue, never()).queue(anyList(), anyList(), any(), any(), anyBoolean());
    }

    @Test
    void noActiveVersionQueuesNothing() {
        when(registry.isEnabledForOrg(VisionModule.ID, ORG)).thenReturn(true);
        when(settings.autoInferModelKeys()).thenReturn(Optional.of(List.of()));
        when(queue.activeVersions(List.of())).thenReturn(List.of());

        assertThat(trigger.onImage(photo)).isEqualTo(VisionJobQueue.Outcome.NONE);
        verify(queue, never()).queue(anyList(), anyList(), eq(VisionJobTrigger.AUTO), isNull(), anyBoolean());
    }
}
