package org.tornotron.echno_backend.modules.vision.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.tornotron.echno_backend.modules.inspections.api.EvidenceFile;
import org.tornotron.echno_backend.modules.vision.VisionModuleEnabled;
import org.tornotron.echno_backend.modules.vision.VisionProperties;
import org.tornotron.echno_backend.modules.vision.domain.CvModelFamily;
import org.tornotron.echno_backend.modules.vision.domain.CvModelTask;
import org.tornotron.echno_backend.modules.vision.domain.CvModelVersionStatus;
import org.tornotron.echno_backend.modules.vision.domain.VisionInferenceJob;
import org.tornotron.echno_backend.modules.vision.domain.VisionJobStatus;
import org.tornotron.echno_backend.modules.vision.domain.VisionJobTrigger;
import org.tornotron.echno_backend.modules.vision.dto.ChangeCvModelVersionStatusRequest;
import org.tornotron.echno_backend.modules.vision.dto.CreateCvModelRequest;
import org.tornotron.echno_backend.modules.vision.dto.CvModelClassDto;
import org.tornotron.echno_backend.modules.vision.dto.RegisterCvModelVersionRequest;
import org.tornotron.echno_backend.modules.vision.repository.VisionInferenceJobRepository;
import org.tornotron.echno_backend.modules.vision.service.CvModelRegistryService;
import org.tornotron.echno_backend.modules.vision.taxonomy.CvClassTaxonomy;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The job table against CockroachDB: one row per image and active version with the registry's
 * snapshot copied, the partial unique index skipping a pending duplicate, a DONE pair skipped
 * unless rerun, unreadable files counted, and the poller's lease recovery.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = VisionModuleEnabled.PROPERTY + "=true")
@EnableConfigurationProperties
@Import({VisionJobQueue.class, CvModelRegistryService.class, CvClassTaxonomy.class, VisionProperties.class,
        UserContextService.class, com.fasterxml.jackson.databind.ObjectMapper.class})
class VisionJobQueueIT extends AbstractIntegrationTest {

    @Autowired
    private VisionJobQueue queue;

    @Autowired
    private CvModelRegistryService registry;

    @Autowired
    private VisionInferenceJobRepository jobs;

    @PersistenceContext
    private EntityManager entityManager;

    private final String key = "ppe-" + UUID.randomUUID().toString().substring(0, 8);
    private Long orgId;
    private List<VisionJobQueue.RunnableVersion> runnable;

    @BeforeEach
    void registerAnActiveVersion() {
        Organization org = new Organization();
        org.setOrganizationName("CV Proof");
        org.setOrganizationAddress("CV Proof address");
        org.setOrganizationEmail("cvproof@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);
        entityManager.flush();
        orgId = org.getId();
        registry.createModel(new CreateCvModelRequest(key, "PPE", CvModelTask.DETECT, CvModelFamily.SAFETY));
        registry.registerVersion(key, new RegisterCvModelVersionRequest("0.1.0",
                List.of(new CvModelClassDto("ppe_helmet", "BOX", new BigDecimal("0.35"))), "Apache-2.0",
                "rf-detr-nano COCO (Apache-2.0)", "s3://echno-models/ppe/0.1.0/model.onnx", "a".repeat(64),
                "onnxruntime", 384, null, null, Map.of("map50", 0.7), "s3://echno-models/runs/x/eval/report.md"));
        registry.changeStatus(key, "0.1.0", new ChangeCvModelVersionStatusRequest(CvModelVersionStatus.ACTIVE, null));
        runnable = queue.activeVersions(List.of(key));
    }

    @Test
    void aJobCopiesTheImageAndTheVersionTheWorkerNeeds() {
        VisionJobQueue.Outcome outcome = queue.queue(List.of(image(1001L)), runnable, VisionJobTrigger.AUTO, null, false);

        assertThat(outcome).isEqualTo(new VisionJobQueue.Outcome(1, 0, 0));
        VisionInferenceJob job = only(1001L);
        assertThat(job.getStatus()).isEqualTo(VisionJobStatus.QUEUED);
        assertThat(job.getModelKey()).isEqualTo(key);
        assertThat(job.getModelVersion()).isEqualTo("0.1.0");
        assertThat(job.getWeightsUri()).isEqualTo("s3://echno-models/ppe/0.1.0/model.onnx");
        assertThat(job.getInputSize()).isEqualTo(384);
        assertThat(job.getClasses()).singleElement().satisfies(c -> {
            assertThat(c.key()).isEqualTo("ppe_helmet");
            assertThat(c.threshold()).isEqualByComparingTo("0.35");
        });
        assertThat(job.getSourceKey()).isEqualTo("inspection/1001.jpg");
        assertThat(job.getContentType()).isEqualTo("image/jpeg");
        assertThat(job.getOutputKey()).isEqualTo("vision/jobs/" + job.getId() + "/detections.json");
        assertThat(job.getTrigger()).isEqualTo(VisionJobTrigger.AUTO);
        assertThat(job.getMaxAttempts()).isEqualTo(3);
        assertThat(job.getProjectId()).isEqualTo(41L);
    }

    @Test
    void aSecondTriggerWhileOneIsPendingIsSkipped() {
        queue.queue(List.of(image(1002L)), runnable, VisionJobTrigger.AUTO, null, false);

        VisionJobQueue.Outcome again = queue.queue(List.of(image(1002L)), runnable, VisionJobTrigger.ON_DEMAND, 5L, true);

        assertThat(again).isEqualTo(new VisionJobQueue.Outcome(0, 1, 0));
        assertThat(jobs.findByAttachmentScoped(1002L)).hasSize(1);
    }

    @Test
    void aFinishedPairRunsAgainOnlyOnARerun() {
        queue.queue(List.of(image(1003L)), runnable, VisionJobTrigger.AUTO, null, false);
        setStatus(only(1003L).getId(), "DONE");

        assertThat(queue.queue(List.of(image(1003L)), runnable, VisionJobTrigger.ON_DEMAND, 5L, false))
                .isEqualTo(new VisionJobQueue.Outcome(0, 1, 0));
        assertThat(queue.queue(List.of(image(1003L)), runnable, VisionJobTrigger.ON_DEMAND, 5L, true))
                .isEqualTo(new VisionJobQueue.Outcome(1, 0, 0));
        assertThat(jobs.findByAttachmentScoped(1003L)).extracting(VisionInferenceJob::getTrigger)
                .containsExactly(VisionJobTrigger.AUTO, VisionJobTrigger.ON_DEMAND);
    }

    @Test
    void filesTheWorkerCannotReadAreCountedOnce() {
        EvidenceFile pdf = new EvidenceFile(orgId, 41L, UUID.randomUUID(), null, 1004L, "inspection/1004.pdf",
                "application/pdf", LocalDateTime.now());
        EvidenceFile gif = new EvidenceFile(orgId, 41L, UUID.randomUUID(), null, 1005L, "inspection/1005.gif",
                "image/gif", LocalDateTime.now());
        EvidenceFile noProject = new EvidenceFile(orgId, null, UUID.randomUUID(), null, 1006L, "inspection/1006.jpg",
                "image/jpeg", LocalDateTime.now());
        EvidenceFile webp = new EvidenceFile(orgId, 41L, null, UUID.randomUUID(), 1007L, "observation/1007.webp",
                "image/webp; charset=binary", LocalDateTime.now());

        VisionJobQueue.Outcome outcome = queue.queue(List.of(pdf, gif, noProject, webp), runnable,
                VisionJobTrigger.ON_DEMAND, 5L, false);

        assertThat(outcome).isEqualTo(new VisionJobQueue.Outcome(1, 0, 3));
        assertThat(only(1007L).getContentType()).isEqualTo("image/webp");
    }

    @Test
    void aRetiredVersionIsNoLongerRunnable() {
        registry.changeStatus(key, "0.1.0", new ChangeCvModelVersionStatusRequest(CvModelVersionStatus.RETIRED, null));

        assertThat(queue.activeVersions(List.of(key))).isEmpty();
    }

    @Test
    void anExpiredLeaseReturnsToTheQueueUntilTheAttemptsRunOut() {
        queue.queue(List.of(image(1008L), image(1009L)), runnable, VisionJobTrigger.AUTO, null, false);
        UUID retry = only(1008L).getId();
        UUID givenUp = only(1009L).getId();
        LocalDateTime expired = LocalDateTime.now().minusMinutes(1);
        setRunning(retry, 1, expired);
        setRunning(givenUp, 3, expired);

        int requeued = jobs.requeueExpiredLeases(LocalDateTime.now());
        int failed = jobs.failExpiredLeases(LocalDateTime.now(), VisionJobPoller.NO_ATTEMPTS_LEFT);
        entityManager.clear();

        assertThat(requeued).isGreaterThanOrEqualTo(1);
        assertThat(failed).isGreaterThanOrEqualTo(1);
        assertThat(jobs.findById(retry).orElseThrow().getStatus()).isEqualTo(VisionJobStatus.QUEUED);
        VisionInferenceJob dead = jobs.findById(givenUp).orElseThrow();
        assertThat(dead.getStatus()).isEqualTo(VisionJobStatus.FAILED);
        assertThat(dead.getError()).isEqualTo(VisionJobPoller.NO_ATTEMPTS_LEFT);
    }

    @Test
    void aDoneJobIsListedForIngestionUntilStamped() {
        queue.queue(List.of(image(1010L)), runnable, VisionJobTrigger.AUTO, null, false);
        UUID id = only(1010L).getId();
        setStatus(id, "DONE");

        assertThat(jobs.findDoneNotIngested(1000)).extracting(VisionInferenceJobRepository.JobRef::getId).contains(id);

        new VisionJobStore(jobs).markIngested(id, 2, null);
        entityManager.flush();

        assertThat(jobs.findDoneNotIngested(1000)).extracting(VisionInferenceJobRepository.JobRef::getId)
                .doesNotContain(id);
    }

    private EvidenceFile image(Long attachmentId) {
        return new EvidenceFile(orgId, 41L, UUID.randomUUID(), null, attachmentId, "inspection/" + attachmentId + ".jpg",
                "image/jpeg", LocalDateTime.now());
    }

    private VisionInferenceJob only(Long attachmentId) {
        entityManager.clear();
        List<VisionInferenceJob> found = jobs.findByAttachmentScoped(attachmentId);
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private void setStatus(UUID id, String status) {
        entityManager.createNativeQuery("UPDATE cv_inference_jobs SET status = :s, finished_at = now() WHERE id = :id")
                .setParameter("s", status).setParameter("id", id).executeUpdate();
        entityManager.clear();
    }

    private void setRunning(UUID id, int attempt, LocalDateTime leaseExpiresAt) {
        entityManager.createNativeQuery("UPDATE cv_inference_jobs SET status = 'RUNNING', attempt = :a, "
                        + "worker_id = 'w1', lease_expires_at = :l WHERE id = :id")
                .setParameter("a", attempt).setParameter("l", leaseExpiresAt).setParameter("id", id).executeUpdate();
        entityManager.clear();
    }
}
