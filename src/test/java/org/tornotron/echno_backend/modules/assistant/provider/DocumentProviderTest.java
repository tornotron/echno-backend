package org.tornotron.echno_backend.modules.assistant.provider;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.assistant.AssistantProperties;
import org.tornotron.echno_backend.modules.assistant.api.EvidenceUnit;
import org.tornotron.echno_backend.modules.assistant.api.ProviderDescriptor;
import org.tornotron.echno_backend.modules.assistant.api.ProviderResult;
import org.tornotron.echno_backend.modules.assistant.api.Question;
import org.tornotron.echno_backend.modules.assistant.api.Scope;
import org.tornotron.echno_backend.modules.assistant.api.Subject;
import org.tornotron.echno_backend.modules.assistant.llm.AssistantLlmClient;
import org.tornotron.echno_backend.modules.assistant.repository.AssistantDocumentUnitRepository;
import org.tornotron.echno_backend.modules.assistant.repository.AssistantDocumentUnitRepository.NearestChunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentProviderTest {

    private final AssistantDocumentUnitRepository unitRepository = mock(AssistantDocumentUnitRepository.class);
    private final AssistantLlmClient llmClient = mock(AssistantLlmClient.class);
    private final AssistantProperties properties = new AssistantProperties();
    private final DocumentProvider provider = new DocumentProvider(unitRepository, llmClient, properties);

    private record TestNearestChunk(
            UUID id,
            Long attachmentId,
            Integer pageNo,
            Integer charStart,
            Integer charEnd,
            String content,
            Double distance
    ) implements NearestChunk {
        @Override public UUID getId() { return id; }
        @Override public Long getAttachmentId() { return attachmentId; }
        @Override public Integer getPageNo() { return pageNo; }
        @Override public Integer getCharStart() { return charStart; }
        @Override public Integer getCharEnd() { return charEnd; }
        @Override public String getContent() { return content; }
        @Override public Double getDistance() { return distance; }
    }

    @Test
    @DisplayName("Descriptor accurately declares subjects, capabilities, and fields")
    void describesItself() {
        ProviderDescriptor descriptor = provider.describe();

        assertThat(descriptor.id()).isEqualTo("document");
        assertThat(descriptor.subjects()).containsExactly(Subject.DOCUMENT);
        assertThat(descriptor.needsPeriod()).isFalse();
        assertThat(descriptor.carriesPersonalData()).isFalse();
        assertThat(descriptor.fields()).extracting("name")
                .containsExactly("content", "attachmentId", "pageNo", "charStart", "charEnd", "distance");
    }

    @Test
    @DisplayName("Unavailable result returned when projectId is missing in scope")
    void unavailableWhenProjectIdMissing() {
        ProviderResult result = provider.retrieve(new Question("What is the slab depth?"),
                new Scope(null, LocalDate.now(), LocalDate.now(), null));

        assertThat(result).isInstanceOf(ProviderResult.Unavailable.class);
        assertThat(((ProviderResult.Unavailable) result).reason()).contains("project is required");
    }

    @Test
    @DisplayName("Filters chunks exceeding distance threshold and formats matching evidence")
    void filtersExceedingDistanceAndFormatsEvidence() {
        TenantContext.setCurrentOrgId(10L);
        try {
            Long projectId = 100L;
            Question question = new Question("What is the concrete mix ratio?");
            Scope scope = new Scope(projectId, LocalDate.now(), LocalDate.now(), null);

            when(llmClient.embed(question.text())).thenReturn(new float[1024]);

            NearestChunk matching = new TestNearestChunk(
                    UUID.randomUUID(), 55L, 1, 0, 200,
                    "Mix ratio for M25 is 1:1:2 by volume.", 0.18);
            NearestChunk distant = new TestNearestChunk(
                    UUID.randomUUID(), 55L, 2, 200, 400,
                    "Plumbing fixtures for master bathroom.", 0.42); // Exceeds default documentMaxDistance 0.35

            when(unitRepository.nearest(any(float[].class), eq(10L), eq(List.of(projectId)), anyInt()))
                    .thenReturn(List.of(matching, distant));

            ProviderResult result = provider.retrieve(question, scope);

            assertThat(result).isInstanceOf(ProviderResult.Evidence.class);
            List<EvidenceUnit> units = ((ProviderResult.Evidence) result).units();
            assertThat(units).hasSize(1);

            EvidenceUnit unit = units.get(0);
            assertThat(unit.providerId()).isEqualTo("document");
            assertThat(unit.source().type()).isEqualTo("attachment");
            assertThat(unit.source().id()).isEqualTo("55");
            assertThat(unit.source().page()).isEqualTo(1);
            assertThat(unit.relevance()).isEqualTo(1.0 - 0.18);
            assertThat(unit.values()).containsEntry("content", "Mix ratio for M25 is 1:1:2 by volume.")
                    .containsEntry("pageNo", 1)
                    .containsEntry("attachmentId", 55L)
                    .containsEntry("distance", 0.18);
        } finally {
            TenantContext.setCurrentOrgId(null);
        }
    }

    @Test
    @DisplayName("Returns Empty when all retrieved chunks exceed distance threshold")
    void returnsEmptyWhenAllChunksDistant() {
        TenantContext.setCurrentOrgId(10L);
        try {
            Long projectId = 100L;
            Question question = new Question("Unrelated query");
            Scope scope = new Scope(projectId, LocalDate.now(), LocalDate.now(), null);

            when(llmClient.embed(anyString())).thenReturn(new float[1024]);
            NearestChunk distant = new TestNearestChunk(
                    UUID.randomUUID(), 55L, 1, 0, 100,
                    "Completely unrelated text.", 0.50);

            when(unitRepository.nearest(any(float[].class), eq(10L), eq(List.of(projectId)), anyInt()))
                    .thenReturn(List.of(distant));

            ProviderResult result = provider.retrieve(question, scope);

            assertThat(result).isInstanceOf(ProviderResult.Empty.class);
            assertThat(((ProviderResult.Empty) result).lookedFor()).contains("documents matching");
        } finally {
            TenantContext.setCurrentOrgId(null);
        }
    }
}
