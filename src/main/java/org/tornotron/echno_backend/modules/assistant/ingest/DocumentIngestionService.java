package org.tornotron.echno_backend.modules.assistant.ingest;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;
import org.tornotron.echno_backend.modules.assistant.AssistantProperties;
import org.tornotron.echno_backend.modules.assistant.domain.AssistantDocumentUnit;
import org.tornotron.echno_backend.modules.assistant.dto.IngestStatusDto;
import org.tornotron.echno_backend.modules.assistant.llm.AssistantLlmClient;
import org.tornotron.echno_backend.modules.assistant.repository.AssistantDocumentUnitRepository;
import org.tornotron.echno_backend.organization.Organization;

/**
 * Orchestrates the extraction, chunking, embedding, and vector persistence of project documents.
 *
 * <p>Validates attachment ownership under the caller's tenant, extracts text via {@link DocumentExtractor},
 * segments into paragraph-aware chunks via {@link Chunker}, deduplicates chunks via SHA-256 content hashes
 * to skip re-embedding unchanged text, and stores {@link AssistantDocumentUnit} entities in CockroachDB.
 */
@Slf4j
@Service
@AssistantModuleEnabled
public class DocumentIngestionService {

    private static final long MAX_DOCUMENT_BYTES = 50_000_000L; // 50 MB

    private final AttachmentService attachmentService;
    private final FileStorageService fileStorageService;
    private final DocumentExtractor documentExtractor;
    private final Chunker chunker;
    private final AssistantLlmClient llmClient;
    private final AssistantDocumentUnitRepository unitRepository;
    private final AssistantProperties properties;
    private final EntityManager entityManager;

    @Value("${digital-ocean.cdn-endpoint:}")
    private String cdnEndpoint;

    public DocumentIngestionService(AttachmentService attachmentService,
                                  FileStorageService fileStorageService,
                                  DocumentExtractor documentExtractor,
                                  @Autowired(required = false) Chunker chunker,
                                  AssistantLlmClient llmClient,
                                  AssistantDocumentUnitRepository unitRepository,
                                  AssistantProperties properties,
                                  EntityManager entityManager) {
        this.attachmentService = attachmentService;
        this.fileStorageService = fileStorageService;
        this.documentExtractor = documentExtractor;
        this.properties = properties;
        this.chunker = chunker != null ? chunker : new Chunker(properties);
        this.llmClient = llmClient;
        this.unitRepository = unitRepository;
        this.entityManager = entityManager;
    }

    /**
     * Ingests an attachment into the assistant vector index.
     *
     * @param attachmentId the id of the attachment in the caller's organization
     * @param projectId    the project id the document belongs to, or null if organization-wide
     * @return status DTO with chunk count
     */
    @Transactional
    public IngestStatusDto ingest(Long attachmentId, Long projectId) {
        Long orgId = TenantContext.getCurrentOrgId();
        if (orgId == null) {
            throw new IllegalStateException("Cannot ingest document without an active tenant context");
        }

        List<AttachmentDto> attachments = attachmentService.getAttachments(List.of(attachmentId));
        if (attachments.isEmpty()) {
            throw new ResourceNotFoundException("Attachment with ID " + attachmentId + " was not found in this organization");
        }
        AttachmentDto attachment = attachments.get(0);

        byte[] bytes = downloadBytes(attachment);
        ExtractedDocument extracted = documentExtractor.extract(bytes, attachment.getContentType(), attachment.getFileName());
        List<DocChunk> chunks = chunker.chunk(extracted);

        if (chunks.isEmpty()) {
            log.info("Document {} yielded 0 text chunks during extraction", attachmentId);
            return new IngestStatusDto(attachmentId, "COMPLETED", 0, "Document contained no extractable text.");
        }

        // Check existing units to reuse unchanged embeddings
        List<AssistantDocumentUnit> existingUnits = unitRepository.findByAttachmentId(attachmentId);
        Map<String, float[]> existingEmbeddingsByHash = new HashMap<>();
        for (AssistantDocumentUnit unit : existingUnits) {
            if (unit.getContentHash() != null && unit.getEmbedding() != null) {
                existingEmbeddingsByHash.put(unit.getContentHash(), unit.getEmbedding());
            }
        }

        // Partition chunks into those needing embedding and those reusing embeddings
        List<DocChunk> chunksNeedingEmbed = new ArrayList<>();
        for (DocChunk chunk : chunks) {
            if (!existingEmbeddingsByHash.containsKey(chunk.contentHash())) {
                chunksNeedingEmbed.add(chunk);
            }
        }

        Map<String, float[]> newlyComputedEmbeddings = new HashMap<>();
        int batchSize = Math.max(1, properties.getEmbeddings().getBatchSize());

        for (int i = 0; i < chunksNeedingEmbed.size(); i += batchSize) {
            int end = Math.min(i + batchSize, chunksNeedingEmbed.size());
            List<DocChunk> batch = chunksNeedingEmbed.subList(i, end);
            List<String> texts = batch.stream().map(DocChunk::content).toList();
            List<float[]> vectors = llmClient.embed(texts);

            for (int j = 0; j < batch.size(); j++) {
                if (j < vectors.size()) {
                    newlyComputedEmbeddings.put(batch.get(j).contentHash(), vectors.get(j));
                }
            }
        }

        // Delete old chunks for this attachment so we replace with the new layout
        if (!existingUnits.isEmpty()) {
            unitRepository.deleteByAttachmentId(attachmentId);
            unitRepository.flush();
        }

        Organization org = entityManager.getReference(Organization.class, orgId);
        String embeddingModel = properties.getEmbeddings().getModel();
        if (embeddingModel == null || embeddingModel.isBlank()) {
            embeddingModel = "default";
        }

        List<AssistantDocumentUnit> toSave = new ArrayList<>(chunks.size());
        for (DocChunk chunk : chunks) {
            float[] vector = existingEmbeddingsByHash.get(chunk.contentHash());
            if (vector == null) {
                vector = newlyComputedEmbeddings.get(chunk.contentHash());
            }
            if (vector == null || vector.length == 0) {
                vector = new float[AssistantDocumentUnit.EMBEDDING_DIMENSION];
                vector[0] = 1.0f;
            }

            AssistantDocumentUnit unit = new AssistantDocumentUnit();
            unit.setOrganization(org);
            unit.setProjectId(projectId);
            unit.setAttachmentId(attachmentId);
            unit.setChunkIndex(chunk.chunkIndex());
            unit.setPageNo(chunk.pageNo());
            unit.setCharStart(chunk.charStart());
            unit.setCharEnd(chunk.charEnd());
            unit.setContent(chunk.content());
            unit.setContentHash(chunk.contentHash());
            unit.setEmbedding(vector);
            unit.setEmbeddingModel(embeddingModel);

            toSave.add(unit);
        }

        unitRepository.saveAll(toSave);
        log.info("Successfully ingested attachment {} into {} assistant document chunks (reused {}, newly embedded {})",
                attachmentId, toSave.size(), chunks.size() - chunksNeedingEmbed.size(), chunksNeedingEmbed.size());

        return new IngestStatusDto(attachmentId, "COMPLETED", toSave.size(), "Document ingested successfully.");
    }

    /**
     * Returns ingestion status and chunk count for an attachment.
     */
    @Transactional(readOnly = true)
    public IngestStatusDto status(Long attachmentId) {
        long count = unitRepository.countByAttachmentId(attachmentId);
        if (count > 0) {
            return new IngestStatusDto(attachmentId, "COMPLETED", (int) count, "Document is indexed and ready.");
        }
        return new IngestStatusDto(attachmentId, "PENDING", 0, "No chunks found for this attachment.");
    }

    private byte[] downloadBytes(AttachmentDto attachment) {
        String url = attachment.getUrl();
        Optional<String> maybeKey = fileStorageService.keyForStoredReference(url);
        String key = maybeKey.orElseGet(() -> extractKey(url));

        Optional<FileStorageService.StoredObject> stored = fileStorageService.readObject(key, MAX_DOCUMENT_BYTES);
        if (stored.isPresent()) {
            return stored.get().content();
        }

        // Fallback: try fileName directly if stored under a standard folder
        Optional<FileStorageService.StoredObject> byName = fileStorageService.readObject(attachment.getFileName(), MAX_DOCUMENT_BYTES);
        if (byName.isPresent()) {
            return byName.get().content();
        }

        throw new IllegalStateException("Failed to download attachment bytes for key: " + key);
    }

    private String extractKey(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String clean = url.trim();
        if (clean.contains("?")) {
            clean = clean.substring(0, clean.indexOf('?'));
        }
        if (cdnEndpoint != null && !cdnEndpoint.isBlank()) {
            String prefix = cdnEndpoint.endsWith("/") ? cdnEndpoint : cdnEndpoint + "/";
            if (clean.startsWith(prefix)) {
                return clean.substring(prefix.length());
            }
        }
        int idx = clean.indexOf("://");
        if (idx != -1) {
            String path = clean.substring(idx + 3);
            int slash = path.indexOf('/');
            if (slash != -1) {
                String subPath = path.substring(slash + 1);
                int secondSlash = subPath.indexOf('/');
                if (secondSlash != -1) {
                    return subPath.substring(secondSlash + 1);
                }
                return subPath;
            }
        }
        return clean.startsWith("/") ? clean.substring(1) : clean;
    }
}
