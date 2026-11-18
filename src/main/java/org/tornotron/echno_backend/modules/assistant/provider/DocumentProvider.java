package org.tornotron.echno_backend.modules.assistant.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;
import org.tornotron.echno_backend.modules.assistant.AssistantProperties;
import org.tornotron.echno_backend.modules.assistant.api.AssistantProvider;
import org.tornotron.echno_backend.modules.assistant.api.CostProfile;
import org.tornotron.echno_backend.modules.assistant.api.EvidenceUnit;
import org.tornotron.echno_backend.modules.assistant.api.FieldSpec;
import org.tornotron.echno_backend.modules.assistant.api.ProviderDescriptor;
import org.tornotron.echno_backend.modules.assistant.api.ProviderResult;
import org.tornotron.echno_backend.modules.assistant.api.Question;
import org.tornotron.echno_backend.modules.assistant.api.Scope;
import org.tornotron.echno_backend.modules.assistant.api.SourceRef;
import org.tornotron.echno_backend.modules.assistant.api.Subject;
import org.tornotron.echno_backend.modules.assistant.llm.AssistantLlmClient;
import org.tornotron.echno_backend.modules.assistant.repository.AssistantDocumentUnitRepository;
import org.tornotron.echno_backend.modules.assistant.repository.AssistantDocumentUnitRepository.NearestChunk;

/**
 * Searches ingested project documents, contracts, specifications, and drawings notes using CockroachDB
 * vector similarity search.
 *
 * <p>The search is scoped strictly to the caller's organization and the requested project. Chunks
 * are filtered against {@code echno.assistant.retrieval.document-max-distance} to ensure only
 * semantically relevant excerpts are cited.
 */
@Slf4j
@Component
@AssistantModuleEnabled
public class DocumentProvider implements AssistantProvider {

    public static final String ID = "document";

    private final AssistantDocumentUnitRepository unitRepository;
    private final AssistantLlmClient llmClient;
    private final AssistantProperties properties;

    public DocumentProvider(AssistantDocumentUnitRepository unitRepository,
                            AssistantLlmClient llmClient,
                            AssistantProperties properties) {
        this.unitRepository = unitRepository;
        this.llmClient = llmClient;
        this.properties = properties;
    }

    @Override
    public ProviderDescriptor describe() {
        return new ProviderDescriptor(
                ID,
                "Answers questions from uploaded project documents, contracts, specifications, and plans using semantic vector search.",
                List.of(
                        new FieldSpec("content", "text", "The matching text excerpt from the document."),
                        new FieldSpec("attachmentId", "id", "The attachment id of the source document."),
                        new FieldSpec("pageNo", "page", "1-based page number where the excerpt appears."),
                        new FieldSpec("charStart", "offset", "Start character index on the page."),
                        new FieldSpec("charEnd", "offset", "End character index on the page."),
                        new FieldSpec("distance", "score", "Vector distance between the query and the chunk.")
                ),
                Set.of(Subject.DOCUMENT),
                false, // needsPeriod
                false, // carriesPersonalData
                new CostProfile(300, 200)
        );
    }

    @Override
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ProviderResult retrieve(Question question, Scope scope) {
        if (scope == null || scope.projectId() == null) {
            return new ProviderResult.Unavailable("A project is required to search project documents.");
        }
        if (question == null || question.text() == null || question.text().isBlank()) {
            return new ProviderResult.Unavailable("Question text is required to search documents.");
        }

        Long orgId = TenantContext.getCurrentOrgId();
        if (orgId == null) {
            return new ProviderResult.Unavailable("An organization is required to search documents.");
        }

        float[] queryVector;
        try {
            queryVector = llmClient.embed(question.text());
        } catch (Exception e) {
            log.error("Failed to embed question for document search: {}", e.getMessage(), e);
            return new ProviderResult.Unavailable("Document embedding model is currently unavailable.");
        }

        if (queryVector == null || queryVector.length == 0) {
            return new ProviderResult.Unavailable("Empty query vector returned by embedding model.");
        }

        int k = properties.getRetrieval().getDocumentK();
        double maxDistance = properties.getRetrieval().getDocumentMaxDistance();

        List<NearestChunk> nearest;
        try {
            nearest = unitRepository.nearest(queryVector, orgId, List.of(scope.projectId()), k);
        } catch (Exception e) {
            log.error("Failed to query nearest document chunks: {}", e.getMessage(), e);
            return new ProviderResult.Unavailable("Document database search failed: " + e.getMessage());
        }

        if (nearest == null || nearest.isEmpty()) {
            return new ProviderResult.Empty("documents matching '" + question.text() + "' in project " + scope.projectId());
        }

        List<EvidenceUnit> units = new ArrayList<>();
        for (NearestChunk chunk : nearest) {
            if (chunk.getDistance() == null || chunk.getDistance() > maxDistance) {
                continue;
            }

            double relevance = Math.max(0.0, 1.0 - chunk.getDistance());
            Map<String, Object> values = Map.of(
                    "content", chunk.getContent() != null ? chunk.getContent() : "",
                    "attachmentId", chunk.getAttachmentId(),
                    "pageNo", chunk.getPageNo() != null ? chunk.getPageNo() : 1,
                    "charStart", chunk.getCharStart() != null ? chunk.getCharStart() : 0,
                    "charEnd", chunk.getCharEnd() != null ? chunk.getCharEnd() : 0,
                    "distance", chunk.getDistance()
            );

            SourceRef source = SourceRef.document(
                    chunk.getAttachmentId(),
                    chunk.getPageNo(),
                    chunk.getCharStart(),
                    chunk.getCharEnd()
            );

            units.add(new EvidenceUnit(
                    "doc:" + chunk.getId(),
                    ID,
                    "document-chunk",
                    values,
                    source,
                    relevance
            ));
        }

        if (units.isEmpty()) {
            return new ProviderResult.Empty("documents matching '" + question.text() + "' in project " + scope.projectId());
        }

        return new ProviderResult.Evidence(units);
    }
}
