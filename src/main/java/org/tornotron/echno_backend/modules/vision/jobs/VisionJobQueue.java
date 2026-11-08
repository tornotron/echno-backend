package org.tornotron.echno_backend.modules.vision.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.data.domain.PageRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.modules.inspections.api.EvidenceFile;
import org.tornotron.echno_backend.modules.vision.VisionModuleEnabled;
import org.tornotron.echno_backend.modules.vision.domain.CvModel;
import org.tornotron.echno_backend.modules.vision.domain.CvModelVersion;
import org.tornotron.echno_backend.modules.vision.domain.CvModelVersionStatus;
import org.tornotron.echno_backend.modules.vision.domain.VisionInferenceJob;
import org.tornotron.echno_backend.modules.vision.domain.VisionJobTrigger;
import org.tornotron.echno_backend.modules.vision.repository.CvModelRepository;
import org.tornotron.echno_backend.modules.vision.repository.CvModelVersionRepository;
import org.tornotron.echno_backend.modules.vision.repository.VisionInferenceJobRepository;

/**
 * Puts inference jobs on the table the worker polls: one row per image and ACTIVE model version.
 *
 * <p>A second trigger for an image and version that already has a pending job is skipped by the
 * partial unique index on {@code (attachment_id, model_version_id)} over QUEUED and RUNNING rows,
 * through {@code ON CONFLICT DO NOTHING}, so two triggers racing each other also leave one row.
 * A version that has already run on an image to DONE is skipped as well unless the caller asks for
 * a rerun, which only the on-demand path can.
 */
@Slf4j
@Service
@VisionModuleEnabled
@RequiredArgsConstructor
public class VisionJobQueue {

    /** What the worker can decode. Everything else is counted as not an image. */
    public static final Set<String> READABLE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    /** The registry is a short platform catalogue; "every family" reads at most this many. */
    static final int MAX_FAMILIES = 200;

    static final String OUTPUT_PREFIX = "vision/jobs/";
    static final String OUTPUT_FILE = "/detections.json";

    private static final String INSERT = """
            INSERT INTO cv_inference_jobs (id, organization_id, project_id, attachment_id, inspection_id,
                observation_id, source_key, content_type, model_version_id, model_key, model_version, weights_uri,
                weights_sha256, input_size, classes, output_key, job_trigger, requested_by_id, status, attempt,
                max_attempts, queued_at, created_at, updated_at)
            VALUES (:id, :organizationId, :projectId, :attachmentId, :inspectionId, :observationId, :sourceKey,
                :contentType, :modelVersionId, :modelKey, :modelVersion, :weightsUri, :weightsSha256, :inputSize,
                CAST(:classes AS JSONB), :outputKey, :trigger, :requestedById, 'QUEUED', 0, :maxAttempts, :now,
                :now, :now)
            ON CONFLICT DO NOTHING
            """;

    private final CvModelRepository models;
    private final CvModelVersionRepository versions;
    private final VisionInferenceJobRepository jobs;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    /** A model version ready to run, with the family key the job row copies. */
    public record RunnableVersion(String modelKey, CvModelVersion version) {}

    /**
     * What a queueing call did.
     *
     * @param queued           rows inserted
     * @param skippedDuplicate image and version pairs that already had a pending job, or a DONE one
     *                         without a rerun
     * @param skippedNotImage  files the worker cannot read (not JPEG, PNG or WebP) or that sit on a
     *                         record with no project, counted once per file
     */
    public record Outcome(int queued, int skippedDuplicate, int skippedNotImage) {

        public static final Outcome NONE = new Outcome(0, 0, 0);
    }

    /**
     * The ACTIVE versions of the named families, or of every family when {@code modelKeys} is empty.
     * A named family with no ACTIVE version, or no row at all, is left out; the caller decides
     * whether that is an error.
     */
    @Transactional(readOnly = true)
    public List<RunnableVersion> activeVersions(Collection<String> modelKeys) {
        List<CvModel> families = new ArrayList<>();
        if (modelKeys == null || modelKeys.isEmpty()) {
            families.addAll(models.findPage(PageRequest.of(0, MAX_FAMILIES)));
        } else {
            for (String key : modelKeys) {
                models.findByModelKey(key).ifPresent(families::add);
            }
        }
        List<RunnableVersion> runnable = new ArrayList<>();
        for (CvModel family : families) {
            versions.findByModelAndStatus(family.getId(), CvModelVersionStatus.ACTIVE).stream()
                    .findFirst()
                    .ifPresent(v -> runnable.add(new RunnableVersion(family.getModelKey(), v)));
        }
        return runnable;
    }

    /**
     * One job per readable file and version. The tenant must be pinned; every file must belong to it.
     *
     * @param rerun queue a version again on an image it already ran on to DONE
     */
    @Transactional
    public Outcome queue(List<EvidenceFile> files, List<RunnableVersion> runnable, VisionJobTrigger trigger,
                         Long requestedById, boolean rerun) {
        int queued = 0;
        int duplicate = 0;
        int notImage = 0;
        Map<UUID, String> classesJson = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        for (EvidenceFile file : files) {
            if (file.projectId() == null || !isReadable(file.contentType())) {
                notImage++;
                continue;
            }
            for (RunnableVersion run : runnable) {
                CvModelVersion v = run.version();
                if (!rerun && jobs.existsDoneScoped(file.attachmentId(), v.getId())) {
                    duplicate++;
                    continue;
                }
                UUID id = UUID.randomUUID();
                int inserted = entityManager.createNativeQuery(INSERT).unwrap(NativeQuery.class)
                        .setParameter("id", id)
                        .setParameter("organizationId", file.organizationId())
                        .setParameter("projectId", file.projectId())
                        .setParameter("attachmentId", file.attachmentId())
                        .setParameter("inspectionId", file.inspectionId(), UUID.class)
                        .setParameter("observationId", file.observationId(), UUID.class)
                        .setParameter("sourceKey", file.storageKey())
                        .setParameter("contentType", normalised(file.contentType()))
                        .setParameter("modelVersionId", v.getId())
                        .setParameter("modelKey", run.modelKey())
                        .setParameter("modelVersion", v.getVersion())
                        .setParameter("weightsUri", v.getWeightsUri())
                        .setParameter("weightsSha256", v.getWeightsSha256())
                        .setParameter("inputSize", v.getInputSize())
                        .setParameter("classes", classesJson.computeIfAbsent(v.getId(), k -> json(v)))
                        .setParameter("outputKey", outputKeyOf(id))
                        .setParameter("trigger", trigger.name())
                        .setParameter("requestedById", requestedById, Long.class)
                        .setParameter("maxAttempts", VisionInferenceJob.DEFAULT_MAX_ATTEMPTS)
                        .setParameter("now", now)
                        .executeUpdate();
                if (inserted == 1) {
                    queued++;
                } else {
                    duplicate++;
                }
            }
        }
        if (queued > 0) {
            log.info("Queued {} vision job(s) ({}), {} duplicate(s) skipped, {} unreadable file(s)", queued, trigger,
                    duplicate, notImage);
        }
        return new Outcome(queued, duplicate, notImage);
    }

    /** Where the worker writes a job's detections, in the attachments bucket. */
    public static String outputKeyOf(UUID jobId) {
        return OUTPUT_PREFIX + jobId + OUTPUT_FILE;
    }

    public static boolean isReadable(String contentType) {
        return contentType != null && READABLE_TYPES.contains(normalised(contentType));
    }

    private static String normalised(String contentType) {
        String type = contentType.toLowerCase(Locale.ROOT).trim();
        int parameters = type.indexOf(';');
        return parameters < 0 ? type : type.substring(0, parameters).trim();
    }

    private String json(CvModelVersion v) {
        try {
            return objectMapper.writeValueAsString(v.getClasses());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write the class list of model version " + v.getId(), e);
        }
    }
}
