package org.tornotron.echno_backend.modules.inspections.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.repository.AttachmentRepository;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.modules.inspections.DefectAnnotationShape;
import org.tornotron.echno_backend.modules.inspections.InspectionCategory;
import org.tornotron.echno_backend.modules.inspections.InspectionType;
import org.tornotron.echno_backend.modules.inspections.ObservationSource;
import org.tornotron.echno_backend.modules.inspections.domain.DefectPhotoAnnotation;
import org.tornotron.echno_backend.modules.inspections.domain.Inspection;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionDefect;
import org.tornotron.echno_backend.modules.inspections.domain.Observation;
import org.tornotron.echno_backend.modules.inspections.domain.OrgTrade;
import org.tornotron.echno_backend.modules.inspections.repositories.DefectPhotoAnnotationRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.InspectionRepository;
import org.tornotron.echno_backend.modules.inspections.repositories.ObservationRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.spatial.SpatialLevel;
import org.tornotron.echno_backend.project.spatial.SpatialNodeService;
import org.tornotron.echno_backend.project.spatial.dto.SpatialPathSegment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The export against mocked rows and a mocked object store (#791): what is refused, what is
 * copied where, what the manifest says, and that a second run copies nothing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DatasetExportServiceTest {

    private static final Long ORG = 7L;
    private static final String EVIDENCE_KEY = "inspection/2026/09/slab-crack.jpg";
    private static final String DEFECT_PHOTO_REF = "https://cdn.example.test/inspection/2026/09/defect-1.jpg";
    private static final String DEFECT_PHOTO_KEY = "inspection/2026/09/defect-1.jpg";
    private static final String OBSERVATION_KEY = "observation/2026/09/rover-frame.png";

    @Mock private OrganizationRepository organizationRepository;
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private InspectionRepository inspectionRepository;
    @Mock private DefectPhotoAnnotationRepository annotationRepository;
    @Mock private ObservationRepository observationRepository;
    @Mock private SpatialNodeService spatialNodeService;
    @Mock private FileStorageService fileStorageService;
    @Mock private DatasetExportRunRepository runRepository;
    @Mock private DatasetExportedItemRepository itemRepository;

    private final DatasetExportProperties properties = new DatasetExportProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private DatasetExportService service;

    private Organization org;
    private Inspection inspection;
    private UUID nodeId;
    private Observation observation;

    @BeforeEach
    void setUp() {
        service = new DatasetExportService(organizationRepository, attachmentRepository, inspectionRepository,
                annotationRepository, observationRepository, spatialNodeService, fileStorageService,
                runRepository, itemRepository, properties, objectMapper);

        org = new Organization();
        org.setId(ORG);
        org.setDatasetConsent(true);
        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(org));

        when(runRepository.save(any(DatasetExportRun.class))).thenAnswer(inv -> {
            DatasetExportRun run = inv.getArgument(0);
            if (run.getId() == null) {
                run.setId(UUID.randomUUID());
            }
            return run;
        });
        when(itemRepository.save(any(DatasetExportedItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.findSourceRefs(eq(ORG), any())).thenReturn(List.of());

        nodeId = UUID.randomUUID();
        inspection = new Inspection();
        inspection.setId(UUID.randomUUID());
        inspection.setOrganization(org);
        inspection.setProjectId(42L);
        inspection.setType(InspectionType.QUALITY);
        inspection.setCategory(InspectionCategory.QA_QC);
        inspection.setSpatialNodeId(nodeId);
        inspection.setActualStartTime(LocalDateTime.of(2026, 9, 1, 9, 30));
        inspection.setCreatedAt(LocalDateTime.of(2026, 9, 1, 8, 0));
        OrgTrade trade = new OrgTrade();
        trade.setCode("concrete");
        trade.setName("Concrete");
        inspection.setTradeRef(trade);

        // one image and one PDF filed as inspection evidence: the PDF is not a training image
        Attachment evidence = attachment(11L, EVIDENCE_KEY, "image/jpeg", 123456L, inspection.getId());
        Attachment permit = attachment(12L, "inspection/2026/09/permit.pdf", "application/pdf", 9999L, inspection.getId());
        when(attachmentRepository.findByEntityTypeAndOrganization_IdOrderByIdAsc("INSPECTION_EVIDENCE", ORG))
                .thenReturn(List.of(evidence, permit));
        when(inspectionRepository.findAllById(anyList())).thenAnswer(inv -> {
            List<UUID> ids = inv.getArgument(0);
            return ids.contains(inspection.getId()) ? List.of(inspection) : List.of();
        });

        // one defect photo, referenced twice across two defects, with two marks drawn on it
        InspectionDefect defect = new InspectionDefect();
        defect.setInspection(inspection);
        defect.setPhotos(new ArrayList<>(List.of(DEFECT_PHOTO_REF)));
        InspectionDefect again = new InspectionDefect();
        again.setInspection(inspection);
        again.setPhotos(new ArrayList<>(List.of(DEFECT_PHOTO_REF)));
        when(inspectionRepository.findDefectsForOrganization(ORG)).thenReturn(List.of(defect, again));
        when(fileStorageService.keyForStoredReference(DEFECT_PHOTO_REF)).thenReturn(Optional.of(DEFECT_PHOTO_KEY));
        when(annotationRepository.findByOrganization_IdOrderByPhotoAscLineOrderAsc(ORG)).thenReturn(List.of(
                mark(inspection.getId(), DEFECT_PHOTO_REF, "0.10", "0.20", "0.30", "0.40", "crack", 0),
                mark(inspection.getId(), DEFECT_PHOTO_REF, "0.50", "0.50", "0.90", "0.95", "honeycombing", 1)));

        // one observation frame from a rover, on its own spatial node
        observation = new Observation();
        observation.setId(UUID.randomUUID());
        observation.setOrganization(org);
        observation.setProjectId(42L);
        observation.setInspectionId(inspection.getId());
        observation.setSource(ObservationSource.ROBOT);
        observation.setSourceDeviceId("rover-01");
        observation.setObservedAt(LocalDateTime.of(2026, 9, 2, 14, 5, 6));
        Attachment frame = attachment(13L, OBSERVATION_KEY, "image/png", 555L, observation.getId());
        when(attachmentRepository.findByEntityTypeAndOrganization_IdOrderByIdAsc("OBSERVATION_EVIDENCE", ORG))
                .thenReturn(List.of(frame));
        when(observationRepository.findAllById(anyList())).thenReturn(List.of(observation));

        when(spatialNodeService.pathsOf(anyCollection())).thenReturn(Map.of(nodeId, List.of(
                new SpatialPathSegment(UUID.randomUUID(), SpatialLevel.BUILDING, "B1", "Tower B"),
                new SpatialPathSegment(UUID.randomUUID(), SpatialLevel.FLOOR, "F4", "Fourth floor"),
                new SpatialPathSegment(nodeId, SpatialLevel.ZONE, null, "Zone B"))));
    }

    @Test
    void refusesAnOrganizationThatHasNotRecordedConsent() {
        org.setDatasetConsent(false);

        assertThatThrownBy(() -> service.runForOrganization(ORG, "user:1"))
                .isInstanceOf(DatasetConsentMissingException.class);

        verify(fileStorageService, never()).copyObjectTo(anyString(), anyString(), anyString());
        verify(runRepository, never()).save(any());
    }

    @Test
    void copiesEachImageOnceToTheRunPrefixAndRecordsIt() {
        DatasetExportRunDto run = service.runForOrganization(ORG, "user:1");

        assertThat(run.status()).isEqualTo(DatasetExportRunStatus.COMPLETED);
        assertThat(run.exportedCount()).isEqualTo(3);
        assertThat(run.skippedCount()).isZero();
        assertThat(run.failedCount()).isZero();
        String prefix = "construction-images/export/" + run.runKey() + "/";
        assertThat(run.manifestKey()).isEqualTo(prefix + "manifest.jsonl");

        // server-side copies, source key from the attachment store, destination in the dataset bucket
        verify(fileStorageService).copyObjectTo(EVIDENCE_KEY, "echno-datasets",
                prefix + "inspection-evidence/att-11-slab-crack.jpg");
        verify(fileStorageService).copyObjectTo(eq(DEFECT_PHOTO_KEY), eq("echno-datasets"),
                eq(prefix + "defect-photos/" + Integer.toHexString(DEFECT_PHOTO_REF.hashCode()) + "-defect-1.jpg"));
        verify(fileStorageService).copyObjectTo(OBSERVATION_KEY, "echno-datasets",
                prefix + "observation-evidence/att-13-rover-frame.png");
        // the PDF was never copied, and the photo referenced by two defects was copied once
        verify(fileStorageService, never()).copyObjectTo(eq("inspection/2026/09/permit.pdf"), anyString(), anyString());

        ArgumentCaptor<DatasetExportedItem> items = ArgumentCaptor.forClass(DatasetExportedItem.class);
        verify(itemRepository, org.mockito.Mockito.times(3)).save(items.capture());
        assertThat(items.getAllValues()).extracting(DatasetExportedItem::getSourceRef)
                .containsExactly("11", DEFECT_PHOTO_REF, "13");
        assertThat(items.getAllValues()).extracting(DatasetExportedItem::getSourceKind)
                .containsExactly(DatasetSourceKind.INSPECTION_EVIDENCE, DatasetSourceKind.DEFECT_PHOTO,
                        DatasetSourceKind.OBSERVATION_EVIDENCE);
    }

    @Test
    void writesOneManifestLinePerImageWithTheAgreedFields() throws Exception {
        DatasetExportRunDto run = service.runForOrganization(ORG, "user:1");
        List<JsonNode> lines = manifestLines(run);
        assertThat(lines).hasSize(3);

        JsonNode evidence = lines.get(0);
        assertThat(fieldNames(evidence)).containsExactly("evidence_id", "source", "source_ref", "attachment_id",
                "object_key", "source_object_key", "org_id", "consent_org_id", "project_id", "inspection_id",
                "spatial_node_id", "spatial_breadcrumb", "trade", "inspection_type", "inspection_category",
                "captured_at", "uploaded_at", "device", "content_type", "file_size", "annotation_version",
                "annotations", "licence", "anonymised", "run_id");
        assertThat(evidence.get("evidence_id").asText()).isEqualTo("inspection_evidence:11");
        assertThat(evidence.get("source").asText()).isEqualTo("inspection_evidence");
        assertThat(evidence.get("source_ref").asText()).isEqualTo("inspection:" + inspection.getId());
        assertThat(evidence.get("attachment_id").asLong()).isEqualTo(11L);
        assertThat(evidence.get("object_key").asText())
                .isEqualTo("construction-images/export/" + run.runKey() + "/inspection-evidence/att-11-slab-crack.jpg");
        assertThat(evidence.get("source_object_key").asText()).isEqualTo(EVIDENCE_KEY);
        assertThat(evidence.get("org_id").asLong()).isEqualTo(ORG);
        assertThat(evidence.get("consent_org_id").asLong()).isEqualTo(ORG);
        assertThat(evidence.get("project_id").asLong()).isEqualTo(42L);
        assertThat(evidence.get("spatial_node_id").asText()).isEqualTo(nodeId.toString());
        assertThat(evidence.get("spatial_breadcrumb").asText()).isEqualTo("B1 > F4 > Zone B");
        assertThat(evidence.get("trade").asText()).isEqualTo("concrete");
        assertThat(evidence.get("inspection_type").asText()).isEqualTo(InspectionType.QUALITY.getValue());
        assertThat(evidence.get("inspection_category").asText()).isEqualTo(InspectionCategory.QA_QC.getValue());
        assertThat(evidence.get("captured_at").asText()).isEqualTo("2026-09-01T09:30:00Z");
        assertThat(evidence.get("uploaded_at").asText()).isEqualTo("2026-08-31T10:00:00Z");
        assertThat(evidence.get("device").isNull()).isTrue();
        assertThat(evidence.get("content_type").asText()).isEqualTo("image/jpeg");
        assertThat(evidence.get("file_size").asLong()).isEqualTo(123456L);
        assertThat(evidence.get("annotation_version").isNull()).isTrue();
        assertThat(evidence.get("annotations").isArray()).isTrue();
        assertThat(evidence.get("annotations")).isEmpty();
        assertThat(evidence.get("licence").asText()).isEqualTo("org-consent");
        assertThat(evidence.get("anonymised").asBoolean()).isFalse();
        assertThat(evidence.get("run_id").asText()).isEqualTo(run.runKey());

        JsonNode frame = lines.get(2);
        assertThat(frame.get("source").asText()).isEqualTo("observation_evidence");
        assertThat(frame.get("source_ref").asText()).isEqualTo("observation:" + observation.getId());
        assertThat(frame.get("device").asText()).isEqualTo("rover-01");
        assertThat(frame.get("captured_at").asText()).isEqualTo("2026-09-02T14:05:06Z");
        assertThat(frame.get("inspection_id").asText()).isEqualTo(inspection.getId().toString());
    }

    @Test
    void carriesTheInspectorsRectanglesAsAnnotationVersionA0() throws Exception {
        DatasetExportRunDto run = service.runForOrganization(ORG, "user:1");
        JsonNode photo = manifestLines(run).get(1);

        assertThat(photo.get("source").asText()).isEqualTo("defect_photo");
        assertThat(photo.get("evidence_id").asText()).isEqualTo("defect_photo:" + DEFECT_PHOTO_REF);
        assertThat(photo.get("attachment_id").isNull()).isTrue();
        assertThat(photo.get("annotation_version").asText()).isEqualTo("a0");
        JsonNode boxes = photo.get("annotations");
        assertThat(boxes).hasSize(2);
        assertThat(fieldNames(boxes.get(0))).containsExactly("shape", "x1", "y1", "x2", "y2", "label", "line_order");
        assertThat(boxes.get(0).get("shape").asText()).isEqualTo(DefectAnnotationShape.RECTANGLE.getValue());
        assertThat(boxes.get(0).get("x1").decimalValue()).isEqualByComparingTo("0.10");
        assertThat(boxes.get(0).get("y2").decimalValue()).isEqualByComparingTo("0.40");
        assertThat(boxes.get(0).get("label").asText()).isEqualTo("crack");
        assertThat(boxes.get(0).get("line_order").asInt()).isZero();
        assertThat(boxes.get(1).get("label").asText()).isEqualTo("honeycombing");
        assertThat(boxes.get(1).get("line_order").asInt()).isEqualTo(1);
    }

    @Test
    void aSecondRunExportsNothingNew() {
        // everything the ledger already holds, as a completed first run would have left it
        when(itemRepository.findSourceRefs(ORG, DatasetSourceKind.INSPECTION_EVIDENCE)).thenReturn(List.of("11"));
        when(itemRepository.findSourceRefs(ORG, DatasetSourceKind.DEFECT_PHOTO)).thenReturn(List.of(DEFECT_PHOTO_REF));
        when(itemRepository.findSourceRefs(ORG, DatasetSourceKind.OBSERVATION_EVIDENCE)).thenReturn(List.of("13"));

        DatasetExportRunDto run = service.runForOrganization(ORG, "schedule");

        assertThat(run.status()).isEqualTo(DatasetExportRunStatus.COMPLETED);
        assertThat(run.exportedCount()).isZero();
        assertThat(run.skippedCount()).isEqualTo(3);
        assertThat(run.manifestKey()).isNull();
        verify(fileStorageService, never()).copyObjectTo(anyString(), anyString(), anyString());
        verify(fileStorageService, never()).putObject(anyString(), anyString(), any(byte[].class), anyString());
        verify(itemRepository, never()).save(any());
    }

    @Test
    void aFailedCopyIsCountedAndTheRunGoesOn() {
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(fileStorageService)
                .copyObjectTo(eq(EVIDENCE_KEY), anyString(), anyString());

        DatasetExportRunDto run = service.runForOrganization(ORG, "user:1");

        assertThat(run.status()).isEqualTo(DatasetExportRunStatus.COMPLETED);
        assertThat(run.failedCount()).isEqualTo(1);
        assertThat(run.exportedCount()).isEqualTo(2);
        verify(fileStorageService).copyObjectTo(eq(OBSERVATION_KEY), anyString(), anyString());
    }

    private List<JsonNode> manifestLines(DatasetExportRunDto run) throws Exception {
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(fileStorageService).putObject(eq("echno-datasets"), eq(run.manifestKey()), body.capture(),
                eq("application/x-ndjson"));
        List<JsonNode> lines = new ArrayList<>();
        for (String line : new String(body.getValue(), StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) {
                lines.add(objectMapper.readTree(line));
            }
        }
        return lines;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }

    private Attachment attachment(Long id, String key, String contentType, Long size, UUID owner) {
        Attachment a = new Attachment();
        a.setId(id);
        a.setStorageKey(key);
        a.setContentType(contentType);
        a.setFileSize(size);
        a.setEntityUuid(owner);
        a.setOrganization(org);
        a.setCreatedAt(LocalDateTime.of(2026, 8, 31, 10, 0));
        return a;
    }

    private static DefectPhotoAnnotation mark(UUID inspectionId, String photo, String x1, String y1, String x2,
                                              String y2, String label, int order) {
        DefectPhotoAnnotation m = new DefectPhotoAnnotation();
        m.setId(UUID.randomUUID());
        m.setInspectionId(inspectionId);
        m.setPhoto(photo);
        m.setShape(DefectAnnotationShape.RECTANGLE);
        m.setX1(new BigDecimal(x1));
        m.setY1(new BigDecimal(y1));
        m.setX2(new BigDecimal(x2));
        m.setY2(new BigDecimal(y2));
        m.setLabel(label);
        m.setLineOrder(order);
        return m;
    }
}
