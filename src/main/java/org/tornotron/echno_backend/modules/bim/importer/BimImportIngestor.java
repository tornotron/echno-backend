package org.tornotron.echno_backend.modules.bim.importer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.modules.bim.BimStorageLayout;
import org.tornotron.echno_backend.modules.bim.BimVersionStatus;
import org.tornotron.echno_backend.modules.bim.domain.BimElement;
import org.tornotron.echno_backend.modules.bim.domain.BimImportJob;
import org.tornotron.echno_backend.modules.bim.domain.BimModel;
import org.tornotron.echno_backend.modules.bim.domain.BimModelVersion;
import org.tornotron.echno_backend.modules.bim.repository.BimElementRepository;
import org.tornotron.echno_backend.modules.bim.repository.BimImportJobRepository;
import org.tornotron.echno_backend.modules.bim.repository.BimModelRepository;
import org.tornotron.echno_backend.modules.bim.repository.BimModelVersionRepository;

/**
 * The transactional halves of an ingestion, run under the job's tenant. {@link #ingest} reads
 * the worker's outputs into the element table with GlobalId matching; {@link #markFailed}
 * records why that did not work in its own transaction, so the failure survives the rollback
 * of the attempt.
 *
 * <p>Matching rule: within a model the IFC GlobalId is the identity. A GlobalId present in
 * this version and in the table is updated in place and keeps its row id, so the spatial
 * node and everything attached to it stay put. A GlobalId not in the table is inserted with
 * both seen columns set to this version. A row whose GlobalId is absent from this version is
 * flagged retired, never deleted; a later version that brings the GlobalId back un-retires it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BimImportIngestor {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final BimImportJobRepository jobs;
    private final BimModelVersionRepository versions;
    private final BimModelRepository models;
    private final BimElementRepository elements;
    private final BimArtifactReader artifacts;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<BimIngestionListener> listeners;

    @Transactional(rollbackFor = Exception.class)
    public BimImportSummary ingest(UUID jobId) throws IOException {
        BimImportJob job = requireJob(jobId);
        BimModelVersion version = versions.findByIdAndModelId(job.getVersionId(), job.getModelId())
                .orElseThrow(() -> new ResourceNotFoundException("BIM model version not found: " + job.getVersionId()));
        BimModel model = models.findByIdScoped(job.getModelId())
                .orElseThrow(() -> new ResourceNotFoundException("BIM model not found: " + job.getModelId()));
        advanceTo(version, BimVersionStatus.INGESTING);

        Map<String, Object> meta = readJson(BimStorageLayout.metaKey(model.getId(), version.getId()));
        Map<String, Object> structure = readJson(BimStorageLayout.structureKey(model.getId(), version.getId()));
        BimImportSummary summary = upsertElements(model, version);

        version.setMeta(meta);
        version.setIfcSchema(asString(meta.get("ifcSchema"), 20));
        version.setElementCount(summary.seen());
        version.setStoreyCount(asInteger(meta.get("storeyCount")));
        version.setError(null);
        for (BimIngestionListener listener : listeners.orderedStream().toList()) {
            listener.afterElementsIngested(model, version, structure, meta);
        }
        version.setImportedAt(LocalDateTime.now());
        version.transitionTo(BimVersionStatus.READY);
        model.setCurrentVersionId(version.getId());
        job.setIngestedAt(LocalDateTime.now());
        log.info("BIM version {} of model {} ingested: {} inserted, {} updated, {} retired",
                version.getVersionNumber(), model.getId(), summary.inserted(), summary.updated(), summary.retired());
        return summary;
    }

    /** Closes a job whose ingestion failed, or whose worker failed, on the version. */
    @Transactional
    public void markFailed(UUID jobId, String error) {
        BimImportJob job = requireJob(jobId);
        versions.findByIdAndModelId(job.getVersionId(), job.getModelId()).ifPresent(version -> {
            if (version.getStatus() != BimVersionStatus.READY) {
                version.setStatus(BimVersionStatus.FAILED);
            }
            version.setError(error);
        });
        if (job.getError() == null) {
            job.setError(error);
        }
        job.setIngestedAt(LocalDateTime.now());
    }

    /** Mirrors a worker's claim on the version: QUEUED becomes PROCESSING. */
    @Transactional
    public void markProcessing(UUID jobId) {
        BimImportJob job = requireJob(jobId);
        versions.findByIdAndModelId(job.getVersionId(), job.getModelId())
                .filter(v -> v.getStatus() == BimVersionStatus.QUEUED)
                .ifPresent(v -> v.transitionTo(BimVersionStatus.PROCESSING));
    }

    // ------------------------------------------------------------------------------------

    private BimImportSummary upsertElements(BimModel model, BimModelVersion version) throws IOException {
        Map<String, BimElement> existing = new HashMap<>();
        for (BimElement e : elements.findByModelId(model.getId())) {
            existing.put(e.getGlobalId(), e);
        }
        Set<String> seen = new HashSet<>();
        int inserted = 0;
        int updated = 0;
        String key = BimStorageLayout.elementsKey(model.getId(), version.getId());
        try (InputStream in = artifacts.open(key);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                BimElementLine parsed;
                try {
                    parsed = objectMapper.readValue(line, BimElementLine.class);
                } catch (IOException e) {
                    throw new IOException("elements.jsonl line " + lineNo + " is not valid: " + e.getMessage(), e);
                }
                if (parsed.globalId() == null || parsed.globalId().isBlank()) {
                    throw new IOException("elements.jsonl line " + lineNo + " has no globalId");
                }
                if (!seen.add(parsed.globalId())) {
                    log.warn("elements.jsonl line {} repeats GlobalId {}; the first occurrence wins", lineNo, parsed.globalId());
                    continue;
                }
                BimElement row = existing.get(parsed.globalId());
                if (row == null) {
                    row = new BimElement();
                    row.setOrganization(model.getOrganization());
                    row.setModelId(model.getId());
                    row.setProjectId(model.getProjectId());
                    row.setGlobalId(parsed.globalId());
                    row.setFirstSeenVersionId(version.getId());
                    inserted++;
                } else {
                    updated++;
                }
                apply(parsed, row);
                row.setLastSeenVersionId(version.getId());
                row.setRetired(false);
                elements.save(row);
            }
        }
        int retired = 0;
        for (BimElement row : existing.values()) {
            if (!seen.contains(row.getGlobalId()) && !row.isRetired()) {
                row.setRetired(true);
                elements.save(row);
                retired++;
            }
        }
        return new BimImportSummary(inserted, updated, retired);
    }

    private static void apply(BimElementLine line, BimElement row) {
        row.setIfcType(truncate(line.ifcType() == null ? "IfcProduct" : line.ifcType(), 100));
        row.setName(truncate(line.name(), 300));
        row.setStoreyGlobalId(truncate(line.storeyGlobalId(), 100));
        row.setSpaceGlobalId(truncate(line.spaceGlobalId(), 100));
        row.setBbox(line.bbox());
        row.setProperties(line.properties());
    }

    private Map<String, Object> readJson(String key) throws IOException {
        try (InputStream in = artifacts.open(key)) {
            return objectMapper.readValue(in, MAP);
        } catch (IOException e) {
            throw new IOException(key.substring(key.lastIndexOf('/') + 1) + " could not be read: " + e.getMessage(), e);
        }
    }

    private BimImportJob requireJob(UUID jobId) {
        return jobs.findByIdScoped(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("BIM import job not found: " + jobId));
    }

    /** Walks the version to the wanted state through whatever intermediate states the machine needs. */
    static void advanceTo(BimModelVersion version, BimVersionStatus target) {
        int guard = 0;
        while (version.getStatus() != target) {
            BimVersionStatus next = switch (version.getStatus()) {
                case FAILED, UPLOADED -> BimVersionStatus.QUEUED;
                case QUEUED -> BimVersionStatus.PROCESSING;
                case PROCESSING -> BimVersionStatus.INGESTING;
                case INGESTING -> BimVersionStatus.READY;
                case READY -> throw new IllegalStateException("Version is already READY");
            };
            version.transitionTo(next);
            if (++guard > 6) {
                throw new IllegalStateException("Could not reach " + target);
            }
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String asString(Object o, int max) {
        return o == null ? null : truncate(o.toString(), max);
    }

    private static Integer asInteger(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }
}
