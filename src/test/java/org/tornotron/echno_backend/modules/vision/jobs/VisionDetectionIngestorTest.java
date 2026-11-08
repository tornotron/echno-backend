package org.tornotron.echno_backend.modules.vision.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.tornotron.echno_backend.modules.inspections.api.EvidenceFile;
import org.tornotron.echno_backend.modules.inspections.api.EvidenceFiles;
import org.tornotron.echno_backend.modules.inspections.api.IntakeActor;
import org.tornotron.echno_backend.modules.inspections.api.IntakeBatchResponse;
import org.tornotron.echno_backend.modules.inspections.api.IntakeObservationRequest;
import org.tornotron.echno_backend.modules.inspections.api.ObservationIntake;
import org.tornotron.echno_backend.modules.inspections.api.ObservationSource;
import org.tornotron.echno_backend.modules.inspections.api.RegionShape;
import org.tornotron.echno_backend.modules.vision.VisionProperties;
import org.tornotron.echno_backend.modules.vision.domain.CvModelClass;
import org.tornotron.echno_backend.modules.vision.domain.VisionInferenceJob;
import org.tornotron.echno_backend.modules.vision.domain.VisionJobStatus;

/**
 * Ingestion of one job's {@code detections.json}: thresholds per class, the per-image cap with the
 * best scores kept, the intake item each detection becomes, and a second pass posting the same
 * external refs so intake's idempotency leaves no duplicate.
 */
class VisionDetectionIngestorTest {

    private static final LocalDateTime UPLOADED = LocalDateTime.of(2026, 11, 8, 9, 30);

    private final VisionJobStore store = mock(VisionJobStore.class);
    private final ObservationIntake intake = mock(ObservationIntake.class);
    private final EvidenceFiles evidenceFiles = mock(EvidenceFiles.class);
    private final VisionProperties properties = new VisionProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private VisionInferenceJob job;
    private String output;
    private VisionDetectionIngestor ingestor;

    @BeforeEach
    void setUp() {
        job = new VisionInferenceJob();
        job.setId(UUID.randomUUID());
        job.setStatus(VisionJobStatus.DONE);
        job.setProjectId(41L);
        job.setAttachmentId(18342L);
        job.setInspectionId(UUID.randomUUID());
        job.setModelKey("ppe");
        job.setModelVersion("0.1.0");
        job.setOutputKey(VisionJobQueue.outputKeyOf(job.getId()));
        job.setClasses(List.of(new CvModelClass("ppe_helmet", "BOX", new BigDecimal("0.35")),
                new CvModelClass("worker", "BOX", new BigDecimal("0.50")),
                new CvModelClass("crack", "POLYGON", new BigDecimal("0.40"))));
        when(store.doneNotIngested(job.getId())).thenReturn(Optional.of(job));
        when(evidenceFiles.find(18342L)).thenReturn(Optional.of(new EvidenceFile(7L, 41L, job.getInspectionId(), null,
                18342L, "inspection/x.jpg", "image/jpeg", UPLOADED)));
        VisionArtifactReader reader = key -> {
            if (output == null || !key.equals(job.getOutputKey())) {
                throw new IOException("NoSuchKey " + key);
            }
            return new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
        };
        ingestor = new VisionDetectionIngestor(store, reader, intake, evidenceFiles, properties, objectMapper);
    }

    @Test
    void detectionsBelowTheirClassThresholdOrOfAnotherClassAreDropped() {
        output = file("""
                {"classKey": "ppe_helmet", "score": 0.34, "shape": "box", "box": [0.1, 0.1, 0.2, 0.2]},
                {"classKey": "ppe_helmet", "score": 0.35, "shape": "box", "box": [0.1, 0.1, 0.2, 0.2]},
                {"classKey": "worker", "score": 0.45, "shape": "box", "box": [0.1, 0.1, 0.2, 0.2]},
                {"classKey": "vehicle", "score": 0.99, "shape": "box", "box": [0.1, 0.1, 0.2, 0.2]}
                """);

        List<IntakeObservationRequest> items = ingestor.itemsOf(job, read());

        assertThat(items).extracting(IntakeObservationRequest::externalRef)
                .containsExactly("cv/" + job.getId() + "/1");
    }

    @Test
    void theCapKeepsTheBestScoresFirst() {
        properties.setMaxObservationsPerImage(2);
        output = file("""
                {"classKey": "ppe_helmet", "score": 0.60, "shape": "box", "box": [0.1, 0.1, 0.2, 0.2]},
                {"classKey": "ppe_helmet", "score": 0.90, "shape": "box", "box": [0.3, 0.1, 0.4, 0.2]},
                {"classKey": "ppe_helmet", "score": 0.40, "shape": "box", "box": [0.5, 0.1, 0.6, 0.2]},
                {"classKey": "ppe_helmet", "score": 0.75, "shape": "box", "box": [0.7, 0.1, 0.8, 0.2]}
                """);

        List<IntakeObservationRequest> items = ingestor.itemsOf(job, read());

        assertThat(items).extracting(IntakeObservationRequest::externalRef)
                .containsExactly("cv/" + job.getId() + "/1", "cv/" + job.getId() + "/3");
    }

    @Test
    void aDetectionBecomesAPendingAiFindingWithOneRegionOnTheJobsImage() {
        output = file("""
                {"classKey": "ppe_helmet", "score": 0.8125, "shape": "box", "box": [0.412, 0.188, 0.471, 1.0000004]},
                {"classKey": "crack", "score": 0.66, "shape": "polygon", "points": [[0.10, 0.52], [0.18, 0.49], [0.21, 0.61]]}
                """);

        List<IntakeObservationRequest> items = ingestor.itemsOf(job, read());

        IntakeObservationRequest helmet = items.get(0);
        assertThat(helmet.source()).isEqualTo(ObservationSource.AI);
        assertThat(helmet.projectId()).isEqualTo(41L);
        assertThat(helmet.inspectionId()).isEqualTo(job.getInspectionId());
        assertThat(helmet.sourceDeviceId()).isEqualTo("echno-vision-worker/0.1.0");
        assertThat(helmet.modelName()).isEqualTo("ppe");
        assertThat(helmet.modelVersion()).isEqualTo("0.1.0");
        assertThat(helmet.confidence()).isEqualByComparingTo("0.8125");
        assertThat(helmet.category()).isEqualTo("ppe_helmet");
        assertThat(helmet.title()).isEqualTo("PPE helmet detected");
        assertThat(helmet.observedAt()).isEqualTo(UPLOADED);
        assertThat(helmet.regions()).singleElement().satisfies(r -> {
            assertThat(r.attachmentId()).isEqualTo(18342L);
            assertThat(r.shape()).isEqualTo(RegionShape.BOX);
            assertThat(r.box()).containsExactly(new BigDecimal("0.412000"), new BigDecimal("0.188000"),
                    new BigDecimal("0.471000"), new BigDecimal("1.000000"));
            assertThat(r.imageWidth()).isEqualTo(4032);
            assertThat(r.imageHeight()).isEqualTo(3024);
            assertThat(r.score()).isEqualByComparingTo("0.8125");
        });
        IntakeObservationRequest crack = items.get(1);
        assertThat(crack.title()).isEqualTo("Crack detected");
        assertThat(crack.regions()).singleElement().satisfies(r -> {
            assertThat(r.shape()).isEqualTo(RegionShape.POLYGON);
            assertThat(r.points()).hasSize(3);
        });
    }

    @Test
    void aSecondPassPostsTheSameReferencesSoNothingIsDuplicated() {
        output = file("""
                {"classKey": "ppe_helmet", "score": 0.8, "shape": "box", "box": [0.1, 0.1, 0.2, 0.2]},
                {"classKey": "worker", "score": 0.9, "shape": "box", "box": [0.3, 0.1, 0.5, 0.6]}
                """);
        Set<String> stored = new HashSet<>();
        List<IntakeBatchResponse> responses = new ArrayList<>();
        when(intake.intakeAll(anyList(), any(IntakeActor.class))).thenAnswer(invocation -> {
            List<IntakeObservationRequest> items = invocation.getArgument(0);
            List<IntakeBatchResponse.Item> results = new ArrayList<>();
            int created = 0;
            for (int i = 0; i < items.size(); i++) {
                boolean isNew = stored.add(items.get(i).externalRef());
                created += isNew ? 1 : 0;
                results.add(new IntakeBatchResponse.Item(i, items.get(i).externalRef(),
                        isNew ? IntakeBatchResponse.Status.CREATED : IntakeBatchResponse.Status.EXISTING,
                        UUID.randomUUID(), null));
            }
            IntakeBatchResponse r = new IntakeBatchResponse(created, items.size() - created, 0, results);
            responses.add(r);
            return r;
        });

        ingestor.ingest(job.getId());
        ingestor.ingest(job.getId());

        assertThat(stored).hasSize(2);
        assertThat(responses.get(1).created()).isZero();
        assertThat(responses.get(1).existing()).isEqualTo(2);
        verify(store, org.mockito.Mockito.times(2)).markIngested(job.getId(), 2, null);
    }

    @Test
    void refusedItemsAreCountedByCodeOnTheJob() {
        output = file("""
                {"classKey": "ppe_helmet", "score": 0.8, "shape": "box", "box": [0.1, 0.1, 0.2, 0.2]},
                {"classKey": "ppe_helmet", "score": 0.7, "shape": "box", "box": [0.3, 0.3, 0.3, 0.4]}
                """);
        when(intake.intakeAll(anyList(), any(IntakeActor.class))).thenReturn(new IntakeBatchResponse(1, 0, 1, List.of(
                new IntakeBatchResponse.Item(0, "a", IntakeBatchResponse.Status.CREATED, UUID.randomUUID(), null),
                new IntakeBatchResponse.Item(1, "b", IntakeBatchResponse.Status.REJECTED, null,
                        new IntakeBatchResponse.Error("INVALID_GEOMETRY", "regions[0].box", "inverted")))));

        ingestor.ingest(job.getId());

        verify(store).markIngested(job.getId(), 1, "1 of 2 detections were refused: INVALID_GEOMETRY (1)");
        ArgumentCaptor<IntakeActor> actor = ArgumentCaptor.forClass(IntakeActor.class);
        verify(intake).intakeAll(anyList(), actor.capture());
        assertThat(actor.getValue().kind()).isEqualTo(IntakeActor.Kind.AI);
        assertThat(actor.getValue().id()).isEqualTo("ppe@0.1.0");
    }

    @Test
    void anUnreadableOutputIsStampedWithItsErrorAndPostsNothing() {
        output = null;

        ingestor.ingest(job.getId());

        verify(intake, never()).intakeAll(anyList(), any());
        verify(store).markIngested(eq(job.getId()), eq(0), org.mockito.ArgumentMatchers.startsWith(
                "Could not read the worker's output: NoSuchKey"));
    }

    @Test
    void aFileNamingAnotherJobIsIgnored() {
        output = "{\"jobId\": \"" + UUID.randomUUID() + "\", \"detections\": []}";

        ingestor.ingest(job.getId());

        verify(intake, never()).intakeAll(anyList(), any());
        verify(store).markIngested(eq(job.getId()), anyInt(), org.mockito.ArgumentMatchers.contains("not this one"));
    }

    @Test
    void aJobWithNothingAboveThresholdIsStampedEmpty() {
        output = file("""
                {"classKey": "worker", "score": 0.1, "shape": "box", "box": [0.1, 0.1, 0.2, 0.2]}
                """);

        ingestor.ingest(job.getId());

        verify(intake, never()).intakeAll(anyList(), any());
        verify(store).markIngested(eq(job.getId()), eq(0), isNull());
    }

    @Test
    void classLabelsReadAsWords() {
        assertThat(VisionDetectionIngestor.labelOf("ppe_vest")).isEqualTo("PPE vest");
        assertThat(VisionDetectionIngestor.labelOf("missing_component")).isEqualTo("Missing component");
    }

    private DetectionsFile read() {
        try {
            return objectMapper.readValue(output, DetectionsFile.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String file(String detections) {
        return "{\"jobId\": \"" + job.getId() + "\", \"modelKey\": \"ppe\", \"modelVersion\": \"0.1.0\", "
                + "\"image\": {\"width\": 4032, \"height\": 3024, \"sha256\": \"abc\", \"exifOrientationApplied\": 6}, "
                + "\"detections\": [" + detections + "], \"inferenceMs\": 214, \"workerVersion\": \"0.1.0\", "
                + "\"runtime\": \"onnxruntime 1.20\", \"futureField\": true}";
    }
}
