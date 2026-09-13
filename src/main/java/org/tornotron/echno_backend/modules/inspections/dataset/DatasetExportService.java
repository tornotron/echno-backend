package org.tornotron.echno_backend.modules.inspections.dataset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.repository.AttachmentRepository;
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

    /**
     * Runs one export for the organization and returns the finished run.
     *
     * <p>Runs under the caller's tenant context (a request, or {@code TenantScopedJobRunner} from
     * the sweep). A failure of one object is counted and the run continues; a failure of the run
     * as a whole is recorded on the row as {@code failed} and the row is still returned, so the
     * sweep's log and the admin screen both see it.
     *
     * @throws DatasetConsentMissingException when the organization has not recorded consent
     */
    @Transactional
    public DatasetExportRunDto runForOrganization(Long organizationId, String triggeredBy) {
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization " + organizationId + " was not found"));
        if (!organization.isDatasetConsent()) {
            throw new DatasetConsentMissingException(organizationId);
        }

        DatasetExportRun run = new DatasetExportRun();
        run.setOrganization(organization);
        run.setRunKey(newRunKey());
        run.setTriggeredBy(triggeredBy);
        run.setStartedAt(LocalDateTime.now(ZoneOffset.UTC));
        run = runRepository.save(run);

        try {
            export(run, organizationId);
            run.setStatus(DatasetExportRunStatus.COMPLETED);
        } catch (RuntimeException e) {
            log.error("Dataset export run {} for organization {} failed: {}", run.getRunKey(), organizationId,
                    e.getMessage(), e);
            run.setStatus(DatasetExportRunStatus.FAILED);
            run.setErrorMessage(truncate(e.getMessage(), 1000));
        }
        run.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
        run = runRepository.save(run);
        log.info("Dataset export run {} for organization {}: {} exported, {} skipped, {} failed",
                run.getRunKey(), organizationId, run.getExportedCount(), run.getSkippedCount(), run.getFailedCount());
        return DatasetExportRunDto.from(run);
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

    private void export(DatasetExportRun run, Long organizationId) {
        List<Candidate> candidates = collectCandidates(organizationId);
        Map<DatasetSourceKind, Set<String>> alreadyExported = new HashMap<>();
        for (DatasetSourceKind kind : DatasetSourceKind.values()) {
            alreadyExported.put(kind, new HashSet<>(itemRepository.findSourceRefs(organizationId, kind)));
        }
        Map<UUID, String> breadcrumbs = breadcrumbsFor(candidates);
        Map<String, List<DefectPhotoAnnotation>> annotationsByPhoto = annotationsByPhoto(organizationId);

        String runPrefix = runPrefix(run.getRunKey());
        StringBuilder manifest = new StringBuilder();
        int exported = 0;
        int skipped = 0;
        int failed = 0;
        int cap = Math.max(1, properties.getMaxObjectsPerRun());

        for (Candidate candidate : candidates) {
            if (alreadyExported.get(candidate.kind()).contains(candidate.sourceRef())) {
                skipped++;
                continue;
            }
            if (exported >= cap) {
                log.info("Dataset export run {} stopped at its per-run cap of {} object(s); the rest are "
                        + "picked up by the next run", run.getRunKey(), cap);
                break;
            }
            String exportKey = runPrefix + candidate.kind().folder() + "/" + candidate.exportName();
            try {
                fileStorageService.copyObjectTo(candidate.sourceKey(), properties.getBucket(), exportKey);
            } catch (RuntimeException e) {
                failed++;
                log.warn("Dataset export run {}: could not copy {} ({}): {}", run.getRunKey(),
                        candidate.sourceKey(), candidate.kind(), e.getMessage());
                continue;
            }

            DatasetExportedItem item = new DatasetExportedItem();
            item.setOrganization(run.getOrganization());
            item.setRunId(run.getId());
            item.setSourceKind(candidate.kind());
            item.setSourceRef(candidate.sourceRef());
            item.setSourceKey(candidate.sourceKey());
            item.setExportKey(exportKey);
            item.setInspectionId(candidate.inspection() == null ? null : candidate.inspection().getId());
            itemRepository.save(item);
            alreadyExported.get(candidate.kind()).add(candidate.sourceRef());

            List<DefectPhotoAnnotation> boxes = candidate.kind() == DatasetSourceKind.DEFECT_PHOTO
                    ? annotationsByPhoto.getOrDefault(photoKey(candidate.inspection().getId(), candidate.sourceRef()), List.of())
                    : List.of();
            manifest.append(toJson(manifestLine(run, candidate, exportKey, breadcrumbs, boxes))).append('\n');
            exported++;
        }

        if (exported > 0) {
            String manifestKey = runPrefix + MANIFEST_FILE;
            fileStorageService.putObject(properties.getBucket(), manifestKey,
                    manifest.toString().getBytes(StandardCharsets.UTF_8), "application/x-ndjson");
            run.setManifestKey(manifestKey);
        }
        run.setExportedCount(exported);
        run.setSkippedCount(skipped);
        run.setFailedCount(failed);
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

    private DatasetManifestLine manifestLine(DatasetExportRun run, Candidate c, String exportKey,
                                             Map<UUID, String> breadcrumbs, List<DefectPhotoAnnotation> boxes) {
        Inspection inspection = c.inspection();
        Observation observation = c.observation();
        Attachment attachment = c.attachment();
        Long orgId = run.getOrganization().getId();
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
                run.getRunKey());
    }

    private static String tradeOf(Inspection inspection) {
        if (inspection.getTradeRef() != null && inspection.getTradeRef().getCode() != null) {
            return inspection.getTradeRef().getCode();
        }
        return inspection.getTrade() == null ? null : inspection.getTrade().name().toLowerCase(Locale.ROOT);
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
