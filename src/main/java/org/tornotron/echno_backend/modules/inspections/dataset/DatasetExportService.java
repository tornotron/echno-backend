package org.tornotron.echno_backend.modules.inspections.dataset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.repository.AttachmentRepository;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.modules.inspections.InspectionEvidence;
import org.tornotron.echno_backend.modules.inspections.ObservationEvidence;
import org.tornotron.echno_backend.modules.inspections.domain.DefectPhotoAnnotation;
import org.tornotron.echno_backend.modules.inspections.domain.Inspection;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionDefect;
import org.tornotron.echno_backend.modules.inspections.domain.Observation;
import org.tornotron.echno_backend.modules.inspections.repositories.DefectPhotoAnnotationRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.InspectionRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.spatial.SpatialNodeService;
import org.tornotron.echno_backend.project.spatial.dto.SpatialPathSegment;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Copies one consenting organization's inspection images into the dataset bucket and writes
 * the run's manifest (#791, work package 2 of the dataset note).
 *
 * <h2>What is exported</h2>
 *
 * <p>Three sources, in this order: {@code INSPECTION_EVIDENCE} attachments, the photo references
 * on {@code InspectionDefect.photos}, and {@code OBSERVATION_EVIDENCE} attachments. Only image
 * content is taken; a permit PDF filed as evidence is not a training image. A defect photo is a
 * stored reference (CDN URL or bare key) rather than an attachment row, so it is resolved to a key
 * through {@link FileStorageService#keyForStoredReference}, and one that does not name an object
 * in our bucket is left out.
 *
 * <h2>Consent and idempotence</h2>
 *
 * <p>The organization's {@code datasetConsent} flag is checked here, not only by the sweep, so an
 * on-demand run on a non-consenting organization is refused with a 409 rather than silently
 * exporting. Every copied object is recorded in {@link DatasetExportedItem}, unique per source
 * reference, and a later run skips what is recorded: re-running exports nothing new, which is what
 * the note's definition of done asks for.
 *
 * <h2>Where it goes</h2>
 *
 * <p>Objects are copied server-side (an S3 copy, no bytes through the JVM) to
 * {@code <prefix>/export/<runKey>/<source folder>/<name>} in the dataset bucket, and one
 * {@code manifest.jsonl} per run sits beside them. Nothing is written under the dataset's
 * immutable {@code raw/} prefix: face and plate anonymisation is the tooling's step, and it is
 * the tooling that promotes an export into {@code raw/}.
 *
 * <h2>How it runs</h2>
 *
 * <p>A run is two steps (#812). {@link #start} records the run row as {@code running} in one
 * short transaction, refusing while another run of the organization is still running, and is
 * what the endpoint answers with. {@link #execute} then does the copying outside any
 * transaction: the rows to copy are read and the manifest lines prepared in one read
 * transaction, the objects are copied one by one, and the ledger rows and the run's counters are
 * committed in batches, so a run that dies half way leaves what it copied on record and the next
 * run skips it. The web endpoint hands {@code execute} to {@link DatasetExportLauncher}; the
 * sweep, which already runs off a scheduler thread, calls {@link #runForOrganization} and gets
 * the two steps in sequence.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetExportService {

    static final String MANIFEST_FILE = "manifest.jsonl";
    private static final DateTimeFormatter RUN_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    private final OrganizationRepository organizationRepository;
    private final AttachmentRepository attachmentRepository;
    private final InspectionRepository inspectionRepository;
    private final DefectPhotoAnnotationRepository annotationRepository;
    private final ObservationRepository observationRepository;
    private final SpatialNodeService spatialNodeService;
    private final FileStorageService fileStorageService;
    private final DatasetExportRunRepository runRepository;
    private final DatasetExportedItemRepository itemRepository;
    private final DatasetExportProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionalWorkRunner transactions;

    /**
     * Starts a run for the organization and runs it to the end on the calling thread.
     *
     * <p>Runs under the caller's tenant context ({@code TenantScopedJobRunner} from the sweep, or
     * a test). A failure of one object is counted and the run continues; a failure of the run as
     * a whole is recorded on the row as {@code failed} and the row is still returned, so the
     * sweep's log and the admin screen both see it.
     *
     * @throws DatasetConsentMissingException when the organization has not recorded consent
     * @throws DatasetExportInProgressException when a run of the organization is still running
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DatasetExportRunDto runForOrganization(Long organizationId, String triggeredBy) {
        DatasetExportRunDto started = start(organizationId, triggeredBy);
        return execute(started.id(), organizationId);
    }

    /**
     * Records a new run as {@code running} and returns it, without copying anything.
     *
     * <p>One run at a time per organization: a second request while one is running is refused
     * with a 409 naming the run, so a second click does not repeat every copy the first has not
     * recorded yet. A run left {@code running} for longer than
     * {@link DatasetExportProperties#getStaleRunningMinutes()} belongs to a process that is
     * gone; it is closed as {@code failed} here and a fresh run is allowed, and the ledger rows
     * it did commit stand, so the fresh run skips them.
     */
    @Transactional
    public DatasetExportRunDto start(Long organizationId, String triggeredBy) {
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization " + organizationId + " was not found"));
        if (!organization.isDatasetConsent()) {
            throw new DatasetConsentMissingException(organizationId);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime staleBefore = now.minusMinutes(Math.max(1, properties.getStaleRunningMinutes()));
        for (DatasetExportRun running : runRepository.findByOrganization_IdAndStatus(organizationId, DatasetExportRunStatus.RUNNING)) {
            if (running.getStartedAt() != null && running.getStartedAt().isAfter(staleBefore)) {
                throw new DatasetExportInProgressException(organizationId, running.getId());
            }
            log.warn("Dataset export run {} for organization {} has been running since {}; the process that ran it "
                    + "is gone, closing it as failed", running.getRunKey(), organizationId, running.getStartedAt());
            running.setStatus(DatasetExportRunStatus.FAILED);
            running.setErrorMessage("Abandoned: still running after " + properties.getStaleRunningMinutes()
                    + " minutes; what it copied is on record and the next run skips it");
            running.setFinishedAt(now);
            runRepository.save(running);
        }

        DatasetExportRun run = new DatasetExportRun();
        run.setOrganization(organization);
        run.setRunKey(newRunKey());
        run.setTriggeredBy(triggeredBy);
        run.setStartedAt(now);
        run = runRepository.save(run);
        return DatasetExportRunDto.from(run);
    }

    /**
     * Runs one started run to its end and returns the finished row.
     *
     * <p>Deliberately not one transaction. The candidates are read and their manifest lines
     * prepared in one read transaction; the copies are S3 calls with no transaction open; the
     * ledger rows and the run's counters are committed every
     * {@link DatasetExportProperties#getBatchSize()} objects; and the row is closed in one
     * last write. A transaction spanning thousands of copies was pushed and restarted by the
     * database, and repeated the whole loop each time.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DatasetExportRunDto execute(UUID runId, Long organizationId) {
        Progress progress = new Progress();
        try {
            ExportPlan plan = transactions.runInTransaction(() -> plan(runId, organizationId));
            progress.skipped = plan.skipped();
            copy(plan, progress);
        } catch (RuntimeException e) {
            log.error("Dataset export run {} for organization {} failed: {}", runId, organizationId, e.getMessage(), e);
            return transactions.runInTransaction(() -> finish(runId, organizationId, progress, DatasetExportRunStatus.FAILED,
                    truncate(e.getMessage(), 1000)));
        }
        return transactions.runInTransaction(() -> finish(runId, organizationId, progress, DatasetExportRunStatus.COMPLETED, null));
    }

    @Transactional(readOnly = true)
    public List<DatasetExportRunDto> listRuns(Long organizationId) {
        return runRepository.findByOrganization_IdOrderByStartedAtDesc(organizationId).stream()
                .map(DatasetExportRunDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DatasetExportRunDto getRun(Long organizationId, UUID runId) {
        return runRepository.findByIdAndOrganization_Id(runId, organizationId)
                .map(DatasetExportRunDto::from)
                .orElseThrow(() -> new ResourceNotFoundException("Dataset export run " + runId + " was not found"));
    }

    /** What one run will copy, resolved while the rows are still attached. */
    record ExportPlan(UUID runId, Long organizationId, String runKey, String runPrefix, List<Planned> planned, int skipped) {
    }

    /** One object to copy, its target key and its manifest line, with no entity behind it. */
    record Planned(DatasetSourceKind kind, String sourceRef, String sourceKey, UUID inspectionId, String exportKey,
                   String manifestLine) {
    }

    /** Counters the batches commit as they go. Only ever touched by the thread running the export. */
    private static final class Progress {
        int exported;
        int skipped;
        int failed;
        String manifestKey;
    }

    private ExportPlan plan(UUID runId, Long organizationId) {
        DatasetExportRun run = runRepository.findByIdAndOrganization_Id(runId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Dataset export run " + runId + " was not found"));
        if (run.getStatus() != DatasetExportRunStatus.RUNNING) {
            throw new IllegalStateException("Dataset export run " + run.getRunKey() + " is " + run.getStatus().getValue()
                    + ", not running");
        }
        List<Candidate> candidates = collectCandidates(organizationId);
        Map<DatasetSourceKind, Set<String>> alreadyExported = new HashMap<>();
        for (DatasetSourceKind kind : DatasetSourceKind.values()) {
            alreadyExported.put(kind, new HashSet<>(itemRepository.findSourceRefs(organizationId, kind)));
        }
        Map<UUID, String> breadcrumbs = breadcrumbsFor(candidates);
        Map<String, List<DefectPhotoAnnotation>> annotationsByPhoto = annotationsByPhoto(organizationId);

        String runPrefix = runPrefix(run.getRunKey());
        int cap = Math.max(1, properties.getMaxObjectsPerRun());
        List<Planned> planned = new ArrayList<>();
        int skipped = 0;
        for (Candidate candidate : candidates) {
            if (alreadyExported.get(candidate.kind()).contains(candidate.sourceRef())) {
                skipped++;
                continue;
            }
            if (planned.size() >= cap) {
                log.info("Dataset export run {} stops at its per-run cap of {} object(s); the rest are "
                        + "picked up by the next run", run.getRunKey(), cap);
                break;
            }
            String exportKey = runPrefix + candidate.kind().folder() + "/" + candidate.exportName();
            List<DefectPhotoAnnotation> boxes = candidate.kind() == DatasetSourceKind.DEFECT_PHOTO
                    ? annotationsByPhoto.getOrDefault(photoKey(candidate.inspection().getId(), candidate.sourceRef()), List.of())
                    : List.of();
            planned.add(new Planned(candidate.kind(), candidate.sourceRef(), candidate.sourceKey(),
                    candidate.inspection() == null ? null : candidate.inspection().getId(), exportKey,
                    toJson(manifestLine(run.getRunKey(), organizationId, candidate, exportKey, breadcrumbs, boxes))));
        }
        return new ExportPlan(runId, organizationId, run.getRunKey(), runPrefix, List.copyOf(planned), skipped);
    }

    private void copy(ExportPlan plan, Progress progress) {
        StringBuilder manifest = new StringBuilder();
        List<Planned> batch = new ArrayList<>();
        int batchSize = Math.max(1, properties.getBatchSize());

        for (Planned item : plan.planned()) {
            try {
                fileStorageService.copyObjectTo(item.sourceKey(), properties.getBucket(), item.exportKey());
            } catch (RuntimeException e) {
                progress.failed++;
                log.warn("Dataset export run {}: could not copy {} ({}): {}", plan.runKey(),
                        item.sourceKey(), item.kind(), e.getMessage());
                continue;
            }
            batch.add(item);
            manifest.append(item.manifestLine()).append('\n');
            if (batch.size() >= batchSize) {
                commitBatch(plan, batch, progress);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            commitBatch(plan, batch, progress);
        }

        if (progress.exported > 0) {
            String manifestKey = plan.runPrefix() + MANIFEST_FILE;
            fileStorageService.putObject(properties.getBucket(), manifestKey,
                    manifest.toString().getBytes(StandardCharsets.UTF_8), "application/x-ndjson");
            progress.manifestKey = manifestKey;
        }
    }

    /** The ledger rows for one batch of copied objects and the run's counters so far, in one transaction. */
    private void commitBatch(ExportPlan plan, List<Planned> batch, Progress progress) {
        List<Planned> copied = List.copyOf(batch);
        transactions.runInTransaction(() -> {
            Organization organization = organizationRepository.getReferenceById(plan.organizationId());
            for (Planned item : copied) {
                DatasetExportedItem row = new DatasetExportedItem();
                row.setOrganization(organization);
                row.setRunId(plan.runId());
                row.setSourceKind(item.kind());
                row.setSourceRef(item.sourceRef());
                row.setSourceKey(item.sourceKey());
                row.setExportKey(item.exportKey());
                row.setInspectionId(item.inspectionId());
                itemRepository.save(row);
            }
            progress.exported += copied.size();
            runRepository.findByIdAndOrganization_Id(plan.runId(), plan.organizationId()).ifPresent(run -> {
                run.setExportedCount(progress.exported);
                run.setSkippedCount(progress.skipped);
                run.setFailedCount(progress.failed);
                runRepository.save(run);
            });
            return null;
        });
    }

    private DatasetExportRunDto finish(UUID runId, Long organizationId, Progress progress, DatasetExportRunStatus status,
                                       String error) {
        DatasetExportRun run = runRepository.findByIdAndOrganization_Id(runId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Dataset export run " + runId + " was not found"));
        run.setStatus(status);
        run.setErrorMessage(error);
        run.setExportedCount(progress.exported);
        run.setSkippedCount(progress.skipped);
        run.setFailedCount(progress.failed);
        run.setManifestKey(progress.manifestKey);
        run.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
        run = runRepository.save(run);
        log.info("Dataset export run {} for organization {}: {}, {} exported, {} skipped, {} failed",
                run.getRunKey(), organizationId, status.getValue(), run.getExportedCount(), run.getSkippedCount(),
                run.getFailedCount());
        return DatasetExportRunDto.from(run);
    }

    /** The three sources, in a stable order, with everything the manifest line needs resolved. */
    private List<Candidate> collectCandidates(Long organizationId) {
        List<Candidate> out = new ArrayList<>();

        List<Attachment> evidence = imagesOnly(attachmentRepository
                .findByEntityTypeAndOrganization_IdOrderByIdAsc(InspectionEvidence.ENTITY_TYPE, organizationId));
        Map<UUID, Inspection> inspections = inspectionsById(
                evidence.stream().map(Attachment::getEntityUuid).toList());
        for (Attachment attachment : evidence) {
            Inspection inspection = inspections.get(attachment.getEntityUuid());
            if (inspection == null) {
                continue;
            }
            out.add(new Candidate(DatasetSourceKind.INSPECTION_EVIDENCE, String.valueOf(attachment.getId()),
                    attachment.getStorageKey(), attachment, inspection, null, null));
        }

        Set<String> seenPhotos = new LinkedHashSet<>();
        for (InspectionDefect defect : inspectionRepository.findDefectsForOrganization(organizationId)) {
            for (String photo : defect.getPhotos()) {
                if (photo == null || !seenPhotos.add(photoKey(defect.getInspection().getId(), photo))) {
                    continue;
                }
                Optional<String> key = fileStorageService.keyForStoredReference(photo);
                if (key.isEmpty()) {
                    log.debug("Defect photo {} on inspection {} is not an object in our bucket; left out",
                            photo, defect.getInspection().getId());
                    continue;
                }
                out.add(new Candidate(DatasetSourceKind.DEFECT_PHOTO, photo, key.get(), null,
                        defect.getInspection(), defect, null));
            }
        }

        List<Attachment> observationEvidence = imagesOnly(attachmentRepository
                .findByEntityTypeAndOrganization_IdOrderByIdAsc(ObservationEvidence.ENTITY_TYPE, organizationId));
        Map<UUID, Observation> observations = observationRepository.findAllById(
                        observationEvidence.stream().map(Attachment::getEntityUuid).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(Observation::getId, Function.identity()));
        Map<UUID, Inspection> observed = inspectionsById(
                observations.values().stream().map(Observation::getInspectionId).toList());
        for (Attachment attachment : observationEvidence) {
            Observation observation = observations.get(attachment.getEntityUuid());
            if (observation == null) {
                continue;
            }
            out.add(new Candidate(DatasetSourceKind.OBSERVATION_EVIDENCE, String.valueOf(attachment.getId()),
                    attachment.getStorageKey(), attachment, observed.get(observation.getInspectionId()), null,
                    observation));
        }
        return out;
    }

    private Map<UUID, Inspection> inspectionsById(List<UUID> ids) {
        List<UUID> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        return inspectionRepository.findAllById(wanted).stream()
                .collect(Collectors.toMap(Inspection::getId, Function.identity()));
    }

    private static List<Attachment> imagesOnly(List<Attachment> attachments) {
        return attachments.stream()
                .filter(a -> a.getStorageKey() != null && !a.getStorageKey().isBlank())
                .filter(a -> a.getContentType() != null && a.getContentType().toLowerCase(Locale.ROOT).startsWith("image/"))
                .toList();
    }

    private Map<UUID, String> breadcrumbsFor(List<Candidate> candidates) {
        Set<UUID> nodeIds = candidates.stream().map(Candidate::spatialNodeId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (nodeIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> out = new HashMap<>();
        spatialNodeService.pathsOf(nodeIds).forEach((id, segments) -> out.put(id, breadcrumb(segments)));
        return out;
    }

    private static String breadcrumb(List<SpatialPathSegment> segments) {
        return segments.stream()
                .map(s -> s.code() != null && !s.code().isBlank() ? s.code() : s.name())
                .collect(Collectors.joining(" > "));
    }

    private Map<String, List<DefectPhotoAnnotation>> annotationsByPhoto(Long organizationId) {
        return annotationRepository.findByOrganization_IdOrderByPhotoAscLineOrderAsc(organizationId).stream()
                .collect(Collectors.groupingBy(a -> photoKey(a.getInspectionId(), a.getPhoto())));
    }

    private static String photoKey(UUID inspectionId, String photo) {
        return inspectionId + "|" + photo;
    }

    private DatasetManifestLine manifestLine(String runKey, Long orgId, Candidate c, String exportKey,
                                             Map<UUID, String> breadcrumbs, List<DefectPhotoAnnotation> boxes) {
        Inspection inspection = c.inspection();
        Observation observation = c.observation();
        Attachment attachment = c.attachment();
        List<DatasetManifestLine.Box> annotations = boxes.stream()
                .map(b -> new DatasetManifestLine.Box(b.getShape() == null ? null : b.getShape().getValue(),
                        b.getX1(), b.getY1(), b.getX2(), b.getY2(), b.getLabel(), b.getLineOrder()))
                .toList();
        return new DatasetManifestLine(
                c.kind().manifestValue() + ":" + c.sourceRef(),
                c.kind().manifestValue(),
                observation != null ? "observation:" + observation.getId()
                        : inspection != null ? "inspection:" + inspection.getId() : null,
                attachment == null ? null : attachment.getId(),
                exportKey,
                c.sourceKey(),
                orgId,
                orgId,
                c.projectId(),
                inspection == null ? null : inspection.getId(),
                c.spatialNodeId(),
                c.spatialNodeId() == null ? null : breadcrumbs.get(c.spatialNodeId()),
                inspection == null ? null : tradeOf(inspection),
                inspection == null || inspection.getType() == null ? null : inspection.getType().getValue(),
                inspection == null || inspection.getCategory() == null ? null : inspection.getCategory().getValue(),
                iso(c.capturedAt()),
                iso(attachment == null ? null : attachment.getCreatedAt()),
                observation == null ? null : observation.getSourceDeviceId(),
                attachment == null ? null : attachment.getContentType(),
                attachment == null ? null : attachment.getFileSize(),
                annotations.isEmpty() ? null : DatasetManifestLine.ANNOTATION_VERSION_A0,
                annotations,
                DatasetManifestLine.LICENCE_ORG_CONSENT,
                false,
                runKey);
    }

    private static String tradeOf(Inspection inspection) {
        return inspection.getTradeRef() == null ? null : inspection.getTradeRef().getCode();
    }

    private static String iso(LocalDateTime at) {
        return at == null ? null : DateTimeFormatter.ISO_INSTANT.format(at.toInstant(ZoneOffset.UTC));
    }

    private String toJson(DatasetManifestLine line) {
        try {
            return objectMapper.writeValueAsString(line);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise a manifest line", e);
        }
    }

    private String runPrefix(String runKey) {
        String prefix = properties.getPrefix() == null ? "" : properties.getPrefix().trim();
        prefix = prefix.replaceAll("^/+|/+$", "");
        return (prefix.isEmpty() ? "" : prefix + "/") + "export/" + runKey + "/";
    }

    private static String newRunKey() {
        return "run-" + RUN_KEY_FORMAT.format(LocalDateTime.now(ZoneOffset.UTC)) + "Z-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** One image to copy, with the rows that describe it. Exactly one of attachment or photo ref applies. */
    record Candidate(DatasetSourceKind kind, String sourceRef, String sourceKey, Attachment attachment,
                     Inspection inspection, InspectionDefect defect, Observation observation) {

        Long projectId() {
            if (observation != null) {
                return observation.getProjectId();
            }
            return inspection == null ? null : inspection.getProjectId();
        }

        UUID spatialNodeId() {
            if (observation != null && observation.getSpatialNodeId() != null) {
                return observation.getSpatialNodeId();
            }
            if (defect != null && defect.getSpatialNodeId() != null) {
                return defect.getSpatialNodeId();
            }
            return inspection == null ? null : inspection.getSpatialNodeId();
        }

        LocalDateTime capturedAt() {
            if (observation != null) {
                return observation.getObservedAt();
            }
            if (inspection == null) {
                return null;
            }
            return inspection.getActualStartTime() != null ? inspection.getActualStartTime() : inspection.getCreatedAt();
        }

        /** A file name that is unique within the run and safe as an object key segment. */
        String exportName() {
            String base = sourceKey;
            int slash = base.lastIndexOf('/');
            if (slash >= 0) {
                base = base.substring(slash + 1);
            }
            String ext = "";
            int dot = base.lastIndexOf('.');
            if (dot > 0 && dot < base.length() - 1) {
                ext = base.substring(dot).toLowerCase(Locale.ROOT);
                base = base.substring(0, dot);
            }
            String safe = base.replaceAll("[^A-Za-z0-9._-]", "_");
            String id = attachment != null ? "att-" + attachment.getId()
                    : Integer.toHexString(sourceRef.hashCode());
            return id + "-" + safe + ext;
        }
    }
}
