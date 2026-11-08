package org.tornotron.echno_backend.modules.vision.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.inspections.api.EvidenceFile;
import org.tornotron.echno_backend.modules.inspections.api.EvidenceFiles;
import org.tornotron.echno_backend.modules.inspections.api.IntakeActor;
import org.tornotron.echno_backend.modules.inspections.api.IntakeBatchResponse;
import org.tornotron.echno_backend.modules.inspections.api.IntakeObservationRequest;
import org.tornotron.echno_backend.modules.inspections.api.ObservationIntake;
import org.tornotron.echno_backend.modules.inspections.api.ObservationRegionRequest;
import org.tornotron.echno_backend.modules.inspections.api.ObservationSource;
import org.tornotron.echno_backend.modules.inspections.api.RegionShape;
import org.tornotron.echno_backend.modules.vision.VisionModuleEnabled;
import org.tornotron.echno_backend.modules.vision.VisionProperties;
import org.tornotron.echno_backend.modules.vision.domain.CvModelClass;
import org.tornotron.echno_backend.modules.vision.domain.VisionInferenceJob;

/**
 * Turns one DONE job's {@code detections.json} into pending observations through the same intake
 * path an external producer uses, so validation, events and idempotency are the same. Each kept
 * detection is one finding with one region on the job's image; its external ref is
 * {@code cv/<jobId>/<index>}, so a crash part way through re-runs cleanly and a second pass, or a
 * second replica, finds the rows already there.
 *
 * <p>Kept means at or above its class threshold in the job's snapshot, best score first, at most
 * {@code echno.vision.max-observations-per-image}. The worker writes everything at or above the
 * lowest threshold, so the file also serves later threshold analysis.
 */
@Slf4j
@Component
@VisionModuleEnabled
@RequiredArgsConstructor
public class VisionDetectionIngestor {

    public static final String DEVICE_PREFIX = "echno-vision-worker/";

    private final VisionJobStore store;
    private final VisionArtifactReader reader;
    private final ObservationIntake intake;
    private final EvidenceFiles evidenceFiles;
    private final VisionProperties properties;
    private final ObjectMapper objectMapper;

    /** Ingests one job. The tenant must be pinned to the job's organization and no transaction open. */
    public void ingest(UUID jobId) {
        Optional<VisionInferenceJob> found = store.doneNotIngested(jobId);
        if (found.isEmpty()) {
            return;
        }
        VisionInferenceJob job = found.get();
        DetectionsFile file;
        try (InputStream in = reader.open(job.getOutputKey())) {
            file = objectMapper.readValue(in, DetectionsFile.class);
        } catch (IOException | RuntimeException e) {
            log.warn("Vision job {}: could not read {}: {}", jobId, job.getOutputKey(), e.getMessage());
            store.markIngested(jobId, 0, oneLine("Could not read the worker's output: " + e.getMessage()));
            return;
        }
        if (file.jobId() != null && !file.jobId().equals(jobId.toString())) {
            store.markIngested(jobId, 0, "The output names job " + file.jobId() + ", not this one");
            return;
        }
        List<IntakeObservationRequest> items = itemsOf(job, file);
        if (items.isEmpty()) {
            store.markIngested(jobId, 0, null);
            return;
        }
        IntakeBatchResponse response = intake.intakeAll(items,
                IntakeActor.ai(job.getModelKey() + "@" + job.getModelVersion()));
        int stored = response.created() + response.existing();
        String error = response.rejected() == 0 ? null : refusalSummary(response, items.size());
        if (error != null) {
            log.warn("Vision job {}: {}", jobId, error);
        }
        store.markIngested(jobId, stored, error);
        log.info("Vision job {} ingested: {} new, {} already there, {} refused", jobId, response.created(),
                response.existing(), response.rejected());
    }

    /** The intake items for a job's kept detections, in the order they are posted. */
    List<IntakeObservationRequest> itemsOf(VisionInferenceJob job, DetectionsFile file) {
        Map<String, CvModelClass> classes = new HashMap<>();
        for (CvModelClass c : job.getClasses()) {
            classes.put(c.key(), c);
        }
        List<IndexedDetection> kept = new ArrayList<>();
        List<DetectionsFile.Detection> detections = file.detections() == null ? List.of() : file.detections();
        for (int i = 0; i < detections.size(); i++) {
            DetectionsFile.Detection d = detections.get(i);
            CvModelClass c = d == null ? null : classes.get(d.classKey());
            if (c == null || d.score() == null) {
                continue;
            }
            BigDecimal threshold = c.threshold() == null ? BigDecimal.ZERO : c.threshold();
            if (BigDecimal.valueOf(d.score()).compareTo(threshold) >= 0) {
                kept.add(new IndexedDetection(i, d));
            }
        }
        kept.sort(Comparator.comparingDouble((IndexedDetection x) -> x.detection().score()).reversed()
                .thenComparingInt(IndexedDetection::index));
        int cap = Math.max(0, properties.getMaxObservationsPerImage());
        if (kept.size() > cap) {
            kept = kept.subList(0, cap);
        }
        LocalDateTime observedAt = observedAt(job);
        String device = DEVICE_PREFIX + (file.workerVersion() == null || file.workerVersion().isBlank()
                ? "unknown" : file.workerVersion());
        Integer width = file.image() == null ? null : positive(file.image().width());
        Integer height = file.image() == null ? null : positive(file.image().height());
        List<IntakeObservationRequest> items = new ArrayList<>(kept.size());
        for (IndexedDetection x : kept) {
            DetectionsFile.Detection d = x.detection();
            BigDecimal score = BigDecimal.valueOf(d.score()).setScale(4, RoundingMode.HALF_UP);
            ObservationRegionRequest region = regionOf(job.getAttachmentId(), d, score, width, height);
            items.add(new IntakeObservationRequest(
                    job.getProjectId(),
                    "cv/" + job.getId() + "/" + x.index(),
                    ObservationSource.AI,
                    truncate(device, 100),
                    null,
                    null,
                    null,
                    null,
                    observedAt,
                    labelOf(d.classKey()) + " detected",
                    null,
                    d.classKey(),
                    null,
                    job.getModelKey(),
                    job.getModelVersion(),
                    score,
                    null,
                    List.of(region),
                    job.getInspectionId()));
        }
        return items;
    }

    private static ObservationRegionRequest regionOf(Long attachmentId, DetectionsFile.Detection d, BigDecimal score,
                                                     Integer width, Integer height) {
        boolean polygon = "polygon".equalsIgnoreCase(d.shape());
        if (polygon) {
            List<List<BigDecimal>> points = new ArrayList<>();
            if (d.points() != null) {
                for (List<Double> p : d.points()) {
                    points.add(p == null ? List.of() : p.stream().map(VisionDetectionIngestor::fraction).toList());
                }
            }
            return new ObservationRegionRequest(attachmentId, RegionShape.POLYGON, null, points, d.classKey(), score,
                    width, height);
        }
        List<BigDecimal> box = d.box() == null ? List.of() : d.box().stream().map(VisionDetectionIngestor::fraction).toList();
        return new ObservationRegionRequest(attachmentId, RegionShape.BOX, box, null, d.classKey(), score, width, height);
    }

    /** A coordinate clamped to [0, 1] at the scale regions store; models overshoot the edge by a hair. */
    private static BigDecimal fraction(Double value) {
        if (value == null || value.isNaN()) {
            return null;
        }
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return BigDecimal.valueOf(clamped).setScale(6, RoundingMode.HALF_UP);
    }

    /** The image's upload time: attachments carry no capture time yet. */
    private LocalDateTime observedAt(VisionInferenceJob job) {
        return evidenceFiles.find(job.getAttachmentId())
                .map(EvidenceFile::uploadedAt)
                .orElseGet(() -> job.getQueuedAt() != null ? job.getQueuedAt() : LocalDateTime.now());
    }

    /** {@code ppe_helmet} reads "PPE helmet", {@code crack} reads "Crack". */
    static String labelOf(String classKey) {
        if (classKey == null || classKey.isBlank()) {
            return "Object";
        }
        String words = classKey.replace('_', ' ').trim();
        if (words.toLowerCase(Locale.ROOT).startsWith("ppe ")) {
            return "PPE " + words.substring(4);
        }
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String refusalSummary(IntakeBatchResponse response, int posted) {
        Map<String, Integer> byCode = new TreeMap<>();
        for (IntakeBatchResponse.Item item : response.results()) {
            if (item.status() == IntakeBatchResponse.Status.REJECTED && item.error() != null) {
                byCode.merge(item.error().code(), 1, Integer::sum);
            }
        }
        StringBuilder codes = new StringBuilder();
        byCode.forEach((code, n) -> codes.append(codes.isEmpty() ? "" : ", ").append(code).append(" (").append(n).append(')'));
        return response.rejected() + " of " + posted + " detections were refused: " + codes;
    }

    private static Integer positive(Integer value) {
        return value != null && value > 0 ? value : null;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String oneLine(String message) {
        String line = message == null ? "" : message.replaceAll("\\s+", " ").trim();
        return truncate(line, 500);
    }

    private record IndexedDetection(int index, DetectionsFile.Detection detection) {}
}
